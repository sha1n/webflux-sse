package com.example.webfluxsse.search.service;

import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import com.example.search.api.model.Event;
import com.example.webfluxsse.search.client.AuthorizationServiceClient;
import com.example.webfluxsse.search.mapper.EventMapper;
import com.example.webfluxsse.search.model.EventEntity;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;

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

    public Flux<Event> searchEventsForUser(String query, String userId, Integer limit) {
        int resultLimit = (limit != null && limit > 0) ? limit : 200;

        if (query == null || query.trim().isEmpty()) {
            // If no query, return events user has access to
            return getEventsForUser(userId, resultLimit);
        }

        // Check if query is an exact phrase match (wrapped in quotes)
        String trimmedQuery = query.trim();
        boolean isExactPhrase = trimmedQuery.startsWith("\"") && trimmedQuery.endsWith("\"") && trimmedQuery.length() > 2;

        String searchQuery;
        Flux<EventEntity> searchResults;

        if (isExactPhrase) {
            // Extract phrase without quotes
            searchQuery = trimmedQuery.substring(1, trimmedQuery.length() - 1);
            log.debug("Exact phrase search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
            searchResults = elasticsearchRepository.searchByExactPhrase(searchQuery);
        } else {
            searchQuery = trimmedQuery;
            log.debug("Full-text search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
            searchResults = elasticsearchRepository.searchByTitleOrDescription(searchQuery);
        }

        // Apply permission filtering and return results
        // Use flatMapSequential with concurrency=4 for parallel permission checks while preserving rank order
        return searchResults
                .bufferTimeout(20, Duration.ofSeconds(5))
                .flatMapSequential(batch -> checkPermissionsBatch(batch, userId), 4)
                .flatMapIterable(Function.identity())
                .take(resultLimit)
                .map(EventMapper::toDto)
                .doOnComplete(() -> log.debug("Search completed for query='{}', userId='{}', returned up to {} results",
                        searchQuery, userId, resultLimit))
                .onErrorResume(error -> {
                    log.error("Elasticsearch search failed for query='{}', userId='{}': {}",
                            searchQuery, userId, error.getMessage());
                    return Flux.empty();
                });
    }

    private Mono<java.util.List<EventEntity>> checkPermissionsBatch(java.util.List<EventEntity> entities, String userId) {
        if (entities.isEmpty()) {
            return Mono.just(java.util.Collections.emptyList());
        }

        java.util.List<Long> eventIds = entities.stream()
                .map(EventEntity::getId)
                .collect(java.util.stream.Collectors.toList());

        log.debug("Checking permissions for {} events, userId='{}'", eventIds.size(), userId);

        // Call authorization-service via REST API
        return authorizationClient.checkBatchPermissions(eventIds, userId)
                .map(BatchPermissionCheckResponse::authorizedEventIds)
                .map(authorizedIds -> entities.stream()
                        .filter(entity -> authorizedIds.contains(entity.getId()))
                        .collect(java.util.stream.Collectors.toList()))
                .doOnNext(filteredEntities -> log.debug("Filtered to {} authorized events for userId='{}'",
                        filteredEntities.size(), userId));
    }

    public Flux<Event> getEventsForUser(String userId, int limit) {
        log.debug("Getting all events for userId='{}', limit={}", userId, limit);

        // Call authorization-service to get event IDs user has access to
        // Collect IDs and query Elasticsearch in a single batch query instead of N+1 queries
        return authorizationClient.getEventIdsForUser(userId)
                .collectList()
                .flatMapMany(eventIds -> {
                    if (eventIds.isEmpty()) {
                        log.debug("No authorized event IDs for userId='{}'", userId);
                        return Flux.empty();
                    }
                    log.debug("Fetching {} events in batch for userId='{}'", eventIds.size(), userId);
                    // Single batch query instead of N individual queries
                    return elasticsearchRepository.findAllById(eventIds);
                })
                .take(limit)
                .map(EventMapper::toDto)
                .doOnComplete(() -> log.debug("Retrieved up to {} events for userId='{}'", limit, userId))
                .onErrorResume(error -> {
                    log.error("Failed to get events for userId='{}': {}", userId, error.getMessage());
                    return Flux.empty();
                });
    }

    public Mono<com.example.webfluxsse.search.model.UserPermissionsResponse> getUserAuthorizedEventDetails(String userId) {
        return authorizationClient.getEventIdsForUser(userId)
                .collectList()
                .map(eventIds -> new com.example.webfluxsse.search.model.UserPermissionsResponse(userId, eventIds.size(), eventIds));
    }
}
