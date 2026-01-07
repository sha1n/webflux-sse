package com.example.virtualthreads.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import com.example.search.api.model.Event;
import com.example.virtualthreads.search.client.AuthorizationServiceClient;
import com.example.virtualthreads.search.mapper.EventMapper;
import com.example.virtualthreads.search.model.EventEntity;
import com.example.virtualthreads.search.repository.elasticsearch.EventElasticsearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int PAGE_SIZE = 50; // Fetch 50 at a time, check permissions in batches
    private final EventElasticsearchRepository elasticsearchRepository;
    private final AuthorizationServiceClient authorizationClient;
    private final ElasticsearchOperations elasticsearchOperations;

    public SearchService(EventElasticsearchRepository elasticsearchRepository,
                         AuthorizationServiceClient authorizationClient,
                         ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchRepository = elasticsearchRepository;
        this.authorizationClient = authorizationClient;
        this.elasticsearchOperations = elasticsearchOperations;
        log.info("SearchService initialized with ElasticsearchOperations for proper pagination");
    }

    public Stream<Event> searchEventsForUser(String query, String userId, Integer limit) {
        int resultLimit = (limit != null && limit > 0) ? limit : 200;

        if (query == null || query.trim().isEmpty()) {
            return getEventsForUser(userId, resultLimit).stream();
        }

        String trimmedQuery = query.trim();
        boolean isExactPhrase = trimmedQuery.startsWith("\"") && trimmedQuery.endsWith("\"") && trimmedQuery.length() > 2;

        String searchQuery;
        if (isExactPhrase) {
            searchQuery = trimmedQuery.substring(1, trimmedQuery.length() - 1);
            log.debug("Exact phrase search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
        } else {
            searchQuery = trimmedQuery;
            log.debug("Full-text search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
        }

        // Create iterator that fetches pages using search_after pattern
        Iterator<Event> iterator = new PaginatedSearchIterator(searchQuery, isExactPhrase, userId, resultLimit);

        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                false
        );
    }

    /**
     * Iterator that implements pagination using ElasticsearchOperations.
     * Fetches pages from Elasticsearch on-demand, checks permissions in batches,
     * and only returns authorized events. Uses proper pagination with from/size parameters.
     */
    private class PaginatedSearchIterator implements Iterator<Event> {
        private final String searchQuery;
        private final boolean exactPhrase;
        private final String userId;
        private final int totalLimit;

        private int currentOffset = 0;
        private int returnedCount = 0;
        private final Queue<Event> buffer = new LinkedList<>();
        private boolean hasMoreResults = true;

        PaginatedSearchIterator(String searchQuery, boolean exactPhrase, String userId, int totalLimit) {
            this.searchQuery = searchQuery;
            this.exactPhrase = exactPhrase;
            this.userId = userId;
            this.totalLimit = totalLimit;
        }

        @Override
        public boolean hasNext() {
            // Fetch next page if buffer is empty and we haven't hit limits
            while (buffer.isEmpty() && hasMoreResults && returnedCount < totalLimit) {
                fetchNextPage();
            }
            return !buffer.isEmpty();
        }

        @Override
        public Event next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            returnedCount++;
            return buffer.poll();
        }

        private void fetchNextPage() {
            try {
                // Build Elasticsearch query using the new Elasticsearch Java client API
                Query elasticsearchQuery = buildMultiMatchQuery(searchQuery, exactPhrase);

                // Create NativeQuery with proper pagination (from/size)
                NativeQuery nativeQuery = NativeQuery.builder()
                        .withQuery(elasticsearchQuery)
                        .withPageable(PageRequest.of(currentOffset / PAGE_SIZE, PAGE_SIZE))
                        .build();

                log.debug("Executing Elasticsearch query: offset={}, size={}, query='{}', userId='{}'",
                        currentOffset, PAGE_SIZE, searchQuery, userId);

                // Execute query using ElasticsearchOperations
                SearchHits<EventEntity> searchHits = elasticsearchOperations.search(nativeQuery, EventEntity.class);

                if (searchHits.isEmpty()) {
                    hasMoreResults = false;
                    log.debug("No more results available for query='{}', userId='{}'", searchQuery, userId);
                    return;
                }

                // Extract entities from search hits
                List<EventEntity> entities = searchHits.getSearchHits().stream()
                        .map(SearchHit::getContent)
                        .collect(Collectors.toList());

                log.debug("Fetched {} results at offset {} for query='{}', userId='{}'",
                        entities.size(), currentOffset, searchQuery, userId);

                // Check permissions for this batch
                List<EventEntity> authorizedEntities = checkPermissionsBatch(entities, userId);

                // Add authorized events to buffer
                authorizedEntities.stream()
                        .map(EventMapper::toDto)
                        .forEach(buffer::offer);

                // Update pagination state
                currentOffset += PAGE_SIZE;

                // Continue if we got a full page (might have more results)
                // Stop if we got fewer results than PAGE_SIZE (last page)
                hasMoreResults = entities.size() == PAGE_SIZE;

            } catch (Exception e) {
                log.error("Error fetching results at offset {} for query='{}', userId='{}': {}",
                        currentOffset, searchQuery, userId, e.getMessage(), e);
                hasMoreResults = false;
            }
        }
    }

    /**
     * Builds an Elasticsearch multi_match query matching the WF implementation exactly.
     */
    private Query buildMultiMatchQuery(String queryText, boolean exactPhrase) {
        MultiMatchQuery.Builder builder = new MultiMatchQuery.Builder()
                .query(queryText)
                .fields("title", "description");

        if (exactPhrase) {
            // Exact phrase match
            builder.type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.Phrase);
        } else {
            // Best fields with OR operator and AUTO fuzziness
            builder.type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                   .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.Or)
                   .fuzziness("AUTO");
        }

        return builder.build()._toQuery();
    }

    /**
     * Checks permissions for a batch of entities.
     *
     * @param entities the batch of entities to check
     * @param userId the user ID to check permissions for
     * @return the filtered list of entities the user has permission to access
     */
    private List<EventEntity> checkPermissionsBatch(List<EventEntity> entities, String userId) {
        if (entities.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> eventIds = entities.stream()
                .map(EventEntity::getId)
                .collect(Collectors.toList());

        log.debug("Checking permissions for {} events, userId='{}'", eventIds.size(), userId);

        // Call authorization-service via REST API with the batch
        BatchPermissionCheckResponse response = authorizationClient.checkBatchPermissions(eventIds, userId);
        Set<Long> authorizedIds = response.authorizedEventIds();

        List<EventEntity> filtered = entities.stream()
                .filter(entity -> authorizedIds.contains(entity.getId()))
                .collect(Collectors.toList());

        log.debug("Filtered to {} authorized events for userId='{}'", filtered.size(), userId);
        return filtered;
    }

    public List<Event> getEventsForUser(String userId, int limit) {
        log.debug("Getting all events for userId='{}', limit={}", userId, limit);
        try {
            List<Long> eventIds = authorizationClient.getEventIdsForUser(userId);
            if (eventIds.isEmpty()) {
                return Collections.emptyList();
            }
            return StreamSupport.stream(elasticsearchRepository.findAllById(eventIds).spliterator(), false)
                    .limit(limit)
                    .map(EventMapper::toDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get events for userId='{}': {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
