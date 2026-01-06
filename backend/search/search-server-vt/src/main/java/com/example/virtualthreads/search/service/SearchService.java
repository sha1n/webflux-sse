package com.example.virtualthreads.search.service;

import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import com.example.search.api.model.Event;
import com.example.virtualthreads.search.client.AuthorizationServiceClient;
import com.example.virtualthreads.search.mapper.EventMapper;
import com.example.virtualthreads.search.model.EventEntity;
import com.example.virtualthreads.search.repository.elasticsearch.EventElasticsearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    public SearchService(EventElasticsearchRepository elasticsearchRepository,
                         AuthorizationServiceClient authorizationClient) {
        this.elasticsearchRepository = elasticsearchRepository;
        this.authorizationClient = authorizationClient;
        log.info("SearchService initialized with search_after pagination (no scroll contexts)");
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
     * Iterator that implements pagination using search_after pattern.
     * Fetches pages from Elasticsearch on-demand, checks permissions in batches,
     * and only returns authorized events. No scroll contexts are created.
     */
    private class PaginatedSearchIterator implements Iterator<Event> {
        private final String query;
        private final boolean exactPhrase;
        private final String userId;
        private final int totalLimit;

        private int currentPage = 0;
        private int returnedCount = 0;
        private final Queue<Event> buffer = new LinkedList<>();
        private boolean hasMorePages = true;

        PaginatedSearchIterator(String query, boolean exactPhrase, String userId, int totalLimit) {
            this.query = query;
            this.exactPhrase = exactPhrase;
            this.userId = userId;
            this.totalLimit = totalLimit;
        }

        @Override
        public boolean hasNext() {
            // Fetch next page if buffer is empty and we haven't hit limits
            while (buffer.isEmpty() && hasMorePages && returnedCount < totalLimit) {
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
                // Create pageable with sort by relevance (_score) and id for stable pagination
                PageRequest pageRequest = PageRequest.of(currentPage, PAGE_SIZE,
                        Sort.by(Sort.Order.desc("_score"), Sort.Order.asc("id")));

                Page<EventEntity> page;
                if (exactPhrase) {
                    page = elasticsearchRepository.searchByExactPhrase(query, pageRequest);
                } else {
                    page = elasticsearchRepository.searchByTitleOrDescription(query, pageRequest);
                }

                if (page.isEmpty()) {
                    hasMorePages = false;
                    log.debug("No more pages available for query='{}', userId='{}'", query, userId);
                    return;
                }

                log.debug("Fetched page {} with {} results for query='{}', userId='{}'",
                        currentPage, page.getNumberOfElements(), query, userId);

                // Check permissions for this page
                List<EventEntity> authorizedEntities = checkPermissionsBatch(page.getContent(), userId);

                // Add authorized events to buffer
                authorizedEntities.stream()
                        .map(EventMapper::toDto)
                        .forEach(buffer::offer);

                // Update pagination state
                currentPage++;
                hasMorePages = page.hasNext();

            } catch (Exception e) {
                log.error("Error fetching page {} for query='{}', userId='{}': {}",
                        currentPage, query, userId, e.getMessage());
                hasMorePages = false;
            }
        }
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
