package com.example.webfluxsse.service;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.repository.r2dbc.UserEventPermissionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class SearchService {

    private final EventElasticsearchRepository elasticsearchRepository;
    private final UserEventPermissionRepository permissionRepository;

    public SearchService(EventElasticsearchRepository elasticsearchRepository,
            UserEventPermissionRepository permissionRepository) {
        this.elasticsearchRepository = elasticsearchRepository;
        this.permissionRepository = permissionRepository;
    }

    public Flux<Event> searchEventsForUser(String query, String userId) {
        if (query == null || query.trim().isEmpty()) {
            // If no query, return events user has access to
            return getEventsForUser(userId);
        }

        // Search in Elasticsearch
        return elasticsearchRepository.findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(query, query)
                .buffer(20)
                .flatMap(batch -> checkPermissionsBatch(batch, userId))
                .flatMapIterable(java.util.function.Function.identity())
                .onErrorResume(error -> {
                    System.err.println("Elasticsearch search failed: " + error.getMessage());
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

        return permissionRepository.findAllByUserIdAndEventIdIn(userId, eventIds)
                .map(com.example.webfluxsse.model.UserEventPermission::getEventId)
                .collect(java.util.stream.Collectors.toSet())
                .map(authorizedIds -> events.stream()
                        .filter(event -> authorizedIds.contains(event.getId()))
                        .collect(java.util.stream.Collectors.toList()));
    }

    public Flux<Event> getEventsForUser(String userId) {
        return permissionRepository.findEventIdsByUserId(userId)
                .flatMap(elasticsearchRepository::findById)
                .onErrorResume(error -> {
                    System.err.println("Elasticsearch lookup failed: " + error.getMessage());
                    return Flux.empty();
                });
    }

    public Mono<Boolean> hasPermission(Long eventId, String userId) {
        return permissionRepository.findByEventIdAndUserId(eventId, userId)
                .hasElement();
    }

}