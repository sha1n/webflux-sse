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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

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

        log.debug("Searching events for query='{}', userId='{}', limit={}", query, userId, resultLimit);

        // Search in Elasticsearch
        return elasticsearchRepository.findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(query, query)
                .bufferTimeout(20, Duration.ofSeconds(5))
                .flatMap(batch -> checkPermissionsBatch(batch, userId))
                .flatMapIterable(java.util.function.Function.identity())
                .take(resultLimit)
                .map(EventMapper::toDto)
                .doOnComplete(() -> log.debug("Search completed for query='{}', userId='{}', returned up to {} results",
                        query, userId, resultLimit))
                .onErrorResume(error -> {
                    log.error("Elasticsearch search failed for query='{}', userId='{}': {}",
                            query, userId, error.getMessage());
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
        return authorizationClient.getEventIdsForUser(userId)
                .flatMap(eventId -> elasticsearchRepository.findById(eventId)
                        .doOnNext(entity -> log.trace("Found event: id={}", entity.getId())))
                .take(limit)
                .map(EventMapper::toDto)
                .doOnComplete(() -> log.debug("Retrieved up to {} events for userId='{}'", limit, userId))
                .onErrorResume(error -> {
                    log.error("Failed to get events for userId='{}': {}", userId, error.getMessage());
                    return Flux.empty();
                });
    }
}
