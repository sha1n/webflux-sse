package com.example.webfluxsse.search.service;

import com.example.webfluxsse.common.dto.BatchPermissionCheckResponse;
import com.example.webfluxsse.common.model.Event;
import com.example.webfluxsse.search.client.AuthorizationServiceClient;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    public Flux<Event> searchEventsForUser(String query, String userId) {
        if (query == null || query.trim().isEmpty()) {
            // If no query, return events user has access to
            return getEventsForUser(userId);
        }

        log.debug("Searching events for query='{}', userId='{}'", query, userId);

        // Search in Elasticsearch
        return elasticsearchRepository.findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(query, query)
                .buffer(20)
                .flatMap(batch -> checkPermissionsBatch(batch, userId))
                .flatMapIterable(java.util.function.Function.identity())
                .doOnComplete(() -> log.debug("Search completed for query='{}', userId='{}'", query, userId))
                .onErrorResume(error -> {
                    log.error("Elasticsearch search failed for query='{}', userId='{}': {}",
                            query, userId, error.getMessage());
                    return Flux.empty();
                });
    }

    private Mono<java.util.List<Event>> checkPermissionsBatch(java.util.List<Event> events, String userId) {
        if (events.isEmpty()) {
            return Mono.just(java.util.Collections.emptyList());
        }

        java.util.List<Long> eventIds = events.stream()
                .map(Event::getId)
                .collect(java.util.stream.Collectors.toList());

        log.debug("Checking permissions for {} events, userId='{}'", eventIds.size(), userId);

        // Call authorization-service via REST API
        return authorizationClient.checkBatchPermissions(eventIds, userId)
                .map(BatchPermissionCheckResponse::authorizedEventIds)
                .map(authorizedIds -> events.stream()
                        .filter(event -> authorizedIds.contains(event.getId()))
                        .collect(java.util.stream.Collectors.toList()))
                .doOnNext(filteredEvents -> log.debug("Filtered to {} authorized events for userId='{}'",
                        filteredEvents.size(), userId));
    }

    public Flux<Event> getEventsForUser(String userId) {
        log.debug("Getting all events for userId='{}'", userId);

        // Call authorization-service to get event IDs user has access to
        return authorizationClient.getEventIdsForUser(userId)
                .flatMap(eventId -> elasticsearchRepository.findById(eventId)
                        .doOnNext(event -> log.trace("Found event: id={}", event.getId())))
                .doOnComplete(() -> log.debug("Retrieved all events for userId='{}'", userId))
                .onErrorResume(error -> {
                    log.error("Failed to get events for userId='{}': {}", userId, error.getMessage());
                    return Flux.empty();
                });
    }
}
