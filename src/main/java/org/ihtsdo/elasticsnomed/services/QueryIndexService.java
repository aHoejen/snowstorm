package org.ihtsdo.elasticsnomed.services;

import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.api.ComponentService;
import io.kaicode.elasticvc.api.VersionControlHelper;
import io.kaicode.elasticvc.domain.Commit;
import org.elasticsearch.index.query.QueryBuilder;
import org.ihtsdo.elasticsnomed.domain.Concepts;
import org.ihtsdo.elasticsnomed.domain.QueryIndexConcept;
import org.ihtsdo.elasticsnomed.domain.Relationship;
import org.ihtsdo.elasticsnomed.repositories.QueryIndexConceptRepository;
import org.ihtsdo.elasticsnomed.services.transitiveclosure.GraphBuilder;
import org.ihtsdo.elasticsnomed.util.TimerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.lang.Long.parseLong;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.index.query.QueryBuilders.termsQuery;

@Service
public class QueryIndexService extends ComponentService {

	public static final int BATCH_SAVE_SIZE = 5000;

	@Autowired
	private ElasticsearchOperations elasticsearchTemplate;

	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private QueryIndexConceptRepository queryIndexConceptRepository;

	@Autowired
	private BranchService branchService;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public Set<Long> retrieveAncestors(String conceptId, String path, boolean stated) {
		final NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(versionControlHelper.getBranchCriteria(path))
						.must(termQuery("conceptId", conceptId))
						.must(termQuery("stated", stated))
				)
				.withPageable(LARGE_PAGE)
				.build();
		final List<QueryIndexConcept> concepts = elasticsearchTemplate.queryForPage(searchQuery, QueryIndexConcept.class).getContent();
		if (concepts.size() > 1) {
			logger.error("More than one index concept found {}", concepts);
			throw new IllegalStateException("More than one query-index-concept found for id " + conceptId + " on branch " + path + ".");
		}
		if (concepts.isEmpty()) {
			throw new IllegalArgumentException(String.format("Concept %s not found on branch %s", conceptId, path));
		}
		return concepts.get(0).getAncestors();
	}

	public Set<Long> retrieveDescendants(String conceptId, QueryBuilder branchCriteria, boolean stated) {
		final NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termQuery("ancestors", conceptId))
						.must(termQuery("stated", stated))
				)
				.withPageable(LARGE_PAGE)
				.build();
		final List<QueryIndexConcept> concepts = elasticsearchTemplate.queryForPage(searchQuery, QueryIndexConcept.class).getContent();
		return concepts.stream().map(QueryIndexConcept::getConceptId).collect(Collectors.toSet());
	}

	public void rebuildStatedAndInferredTransitiveClosures(String branch) {
		Commit commit = branchService.openCommit(branch);
		QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(commit.getBranch());
		updateTransitiveClosure(true, branchCriteria, commit, true);
		updateTransitiveClosure(false, branchCriteria, commit, true);
		branchService.completeCommit(commit);
	}

	void updateStatedAndInferredTransitiveClosures(Commit commit) {
		QueryBuilder relationshipBranchCriteria = versionControlHelper.getBranchCriteriaOpenCommitChangesOnly(commit);
		updateTransitiveClosure(true, relationshipBranchCriteria, commit, false);
		updateTransitiveClosure(false, relationshipBranchCriteria, commit, false);
	}

	private void updateTransitiveClosure(boolean stated, QueryBuilder relationshipBranchCriteria, Commit commit, boolean rebuild) {
		String formName;
		Set<String> characteristicTypeIds = new HashSet<>();
		if (stated) {
			formName = "stated";
			characteristicTypeIds.add(Concepts.STATED_RELATIONSHIP);
			characteristicTypeIds.add(Concepts.ADDITIONAL_RELATIONSHIP);
		} else {
			formName = "inferred";
			characteristicTypeIds.add(Concepts.INFERRED_RELATIONSHIP);
		}

		logger.info("Performing {} of {} transitive closures", rebuild ? "rebuild" : "incremental update", formName);

		TimerUtil timer = new TimerUtil("TC index " + formName);
		Set<Long> existingAncestors = new HashSet<>();
		Set<Long> existingDescendants = new HashSet<>();
		QueryBuilder branchCriteriaForAlreadyCommittedContent = versionControlHelper.getBranchCriteria(commit.getBranch().getFatPath());
		timer.checkpoint("get branch criteria");
		if (!rebuild) {
			// Step: Collect source and destinations of changed is-a relationships
			Set<Long> updateSource = new HashSet<>();
			Set<Long> updateDestination = new HashSet<>();
			try (final CloseableIterator<Relationship> changedInferredIsARelationships = elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
					.withQuery(boolQuery()
							.must(relationshipBranchCriteria)
							.must(termQuery("typeId", Concepts.ISA))
							.must(termsQuery("characteristicTypeId", characteristicTypeIds))
					)
					.withPageable(ConceptService.LARGE_PAGE).build(), Relationship.class)) {
				changedInferredIsARelationships.forEachRemaining(relationship -> {
					updateSource.add(parseLong(relationship.getSourceId()));
					updateDestination.add(parseLong(relationship.getDestinationId()));
				});
			}
			existingAncestors.addAll(updateDestination);
			timer.checkpoint("Collect changed relationships.");

			if (updateSource.isEmpty()) {
				logger.info("No {} changes found. Nothing to do.", formName);
				return;
			}

			// Step: Identify existing TC of updated nodes
			// Strategy: Find existing nodes where ID matches updated relationship source ids, record TC
			NativeSearchQuery query = new NativeSearchQueryBuilder()
					.withQuery(boolQuery()
							.must(branchCriteriaForAlreadyCommittedContent)
							.must(termsQuery("stated", stated))
					)
					.withFilter(boolQuery()
							.should(termsQuery("conceptId", updateDestination)))
					.withPageable(ConceptService.LARGE_PAGE).build();
			try (final CloseableIterator<QueryIndexConcept> existingIndexConcepts = elasticsearchTemplate.stream(query, QueryIndexConcept.class)) {
				existingIndexConcepts.forEachRemaining(indexConcept -> {
					existingAncestors.addAll(indexConcept.getAncestors());
				});
			}
			timer.checkpoint("Collect existingAncestors from QueryIndexConcept.");

			// Step: Identify existing descendants
			// Strategy: Find existing nodes where TC matches updated relationship source ids
			try (final CloseableIterator<QueryIndexConcept> existingIndexConcepts = elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
					.withQuery(boolQuery()
							.must(branchCriteriaForAlreadyCommittedContent)
							.must(termsQuery("stated", stated))
					)
					.withFilter(boolQuery()
							.should(termsQuery("ancestors", updateSource)))
					.withPageable(ConceptService.LARGE_PAGE).build(), QueryIndexConcept.class)) {
				existingIndexConcepts.forEachRemaining(indexConcept -> {
					existingDescendants.add(indexConcept.getConceptId());
				});
			}
			timer.checkpoint("Collect existingDescendants from QueryIndexConcept.");
		}

		logger.info("{} existing ancestors and {} existing descendants of updated relationships identified.", existingAncestors.size(), existingDescendants.size());

		// Step: Build existing graph
		// Strategy: Find relationships of existing TC and descendant nodes and build existing graph(s)
		final GraphBuilder graphBuilder = new GraphBuilder();
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteriaForAlreadyCommittedContent)
						.must(termQuery("active", true))
						.must(termQuery("typeId", Concepts.ISA))
						.must(termsQuery("characteristicTypeId", characteristicTypeIds))
				)
				.withPageable(ConceptService.LARGE_PAGE);
		if (!rebuild) {
			queryBuilder.withFilter(boolQuery().must(termsQuery("sourceId", Sets.union(existingAncestors, existingDescendants))));
		}
		try (final CloseableIterator<Relationship> existingInferredIsARelationships = elasticsearchTemplate.stream(queryBuilder.build(), Relationship.class)) {
			existingInferredIsARelationships.forEachRemaining(relationship ->
					graphBuilder.addParent(parseLong(relationship.getSourceId()), parseLong(relationship.getDestinationId()))
			);
		}
		timer.checkpoint("Build existing nodes from Relationships.");
		logger.info("{} existing nodes loaded.", graphBuilder.getNodeCount());


		// Step - Update graph
		// Strategy: Add/remove edges from new commit
		AtomicLong relationshipsAdded = new AtomicLong();
		AtomicLong relationshipsRemoved = new AtomicLong();
		try (final CloseableIterator<Relationship> existingInferredIsARelationships = elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(relationshipBranchCriteria)
						.must(termQuery("typeId", Concepts.ISA))
						.must(termsQuery("characteristicTypeId", characteristicTypeIds))
				)
				.withPageable(ConceptService.LARGE_PAGE).build(), Relationship.class)) {
			existingInferredIsARelationships.forEachRemaining(relationship -> {
				if (relationship.isActive()) {
					graphBuilder.addParent(parseLong(relationship.getSourceId()), parseLong(relationship.getDestinationId()));
					relationshipsAdded.incrementAndGet();
				} else {
					graphBuilder.removeParent(parseLong(relationship.getSourceId()), parseLong(relationship.getDestinationId()));
					relationshipsRemoved.incrementAndGet();
				}
			});
		}
		timer.checkpoint("Update graph using changed Relationships.");
		logger.info("{} {} is-a relationships added, {} removed.", relationshipsAdded.get(), formName, relationshipsRemoved.get());


		// Step: Save changes
		Set<QueryIndexConcept> indexConceptsToSave = new HashSet<>();
		graphBuilder.getNodes().forEach(node -> {
			final Set<Long> transitiveClosure = node.getTransitiveClosure();
			final Long nodeId = node.getId();
			indexConceptsToSave.add(new QueryIndexConcept(nodeId, transitiveClosure, stated));
		});
		if (!indexConceptsToSave.isEmpty()) {
			for (List<QueryIndexConcept> queryIndexConcepts : Iterables.partition(indexConceptsToSave, 10000)) {
				doSaveBatch(queryIndexConcepts, commit);
			}
		}
		timer.checkpoint("Save updated QueryIndexConcepts");
		logger.info("{} {} concept transitive closures updated.", indexConceptsToSave.size(), formName);

		timer.finish();
	}

	public void doSaveBatch(Collection<QueryIndexConcept> indexConcepts, Commit commit) {
		doSaveBatchComponents(indexConcepts, commit, "conceptId", queryIndexConceptRepository);
	}

	public void deleteAll() {
		queryIndexConceptRepository.deleteAll();
	}
}
