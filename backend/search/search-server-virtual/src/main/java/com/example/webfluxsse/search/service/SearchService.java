package com.example.webfluxsse.search.service;

import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.client.AuthorizationServiceClient;
import com.example.webfluxsse.search.mapper.EventMapper;
import com.example.webfluxsse.search.model.EventEntity;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final EventElasticsearchRepository elasticsearchRepository;
    private final AuthorizationServiceClient authorizationClient;

    public SearchService(EventElasticsearchRepository elasticsearchRepository,
                         AuthorizationServiceClient authorizationClient) {
        this.elasticsearchRepository = elasticsearchRepository;
        this.authorizationClient = authorizationClient;
        log.info("SearchService initialized with authorization-service REST API integration");
    }

    public Stream<Event> searchEventsForUser(String query, String userId, Integer limit) {
        int resultLimit = (limit != null && limit > 0) ? limit : 200;

        if (query == null || query.trim().isEmpty()) {
            return getEventsForUser(userId, resultLimit).stream();
        }

        String trimmedQuery = query.trim();
        boolean isExactPhrase = trimmedQuery.startsWith("\"") && trimmedQuery.endsWith("\"") && trimmedQuery.length() > 2;

        String searchQuery;
        Stream<EventEntity> searchResults;

        if (isExactPhrase) {
            searchQuery = trimmedQuery.substring(1, trimmedQuery.length() - 1);
            log.debug("Exact phrase search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
            searchResults = elasticsearchRepository.searchByExactPhrase(searchQuery);
        } else {
            searchQuery = trimmedQuery;
            log.debug("Full-text search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
            searchResults = elasticsearchRepository.searchByTitleOrDescription(searchQuery);
        }

        // Apply batched permission filtering (equivalent to WebFlux's bufferTimeout)
        return batchedPermissionFilter(searchResults, userId, 20)
                .limit(resultLimit)
                .map(EventMapper::toDto);
    }

    /**
     * Filters a stream of events by checking permissions in batches.
     * Equivalent to WebFlux's bufferTimeout(20, Duration.ofSeconds(5)) followed by flatMap.
     *
     * @param stream the stream of events to filter
     * @param userId the user ID to check permissions for
     * @param batchSize the number of events to batch together for permission checks
     * @return a stream of events the user has permission to access
     */
    private Stream<EventEntity> batchedPermissionFilter(Stream<EventEntity> stream, String userId, int batchSize) {
        List<EventEntity> allEntities = stream.collect(Collectors.toList());

        // Process entities in batches
        return partitionList(allEntities, batchSize).stream()
                .flatMap(batch -> checkPermissionsBatch(batch, userId).stream())
                .sequential();
    }

    /**
     * Partitions a list into smaller batches.
     *
     * @param list the list to partition
     * @param batchSize the size of each batch
     * @return a list of batches
     */
    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }

    /**
     * Checks permissions for a batch of entities.
     * Equivalent to WebFlux's checkPermissionsBatch method.
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
