package com.example.webfluxsse.service;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.repository.r2dbc.UserEventPermissionRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
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
            .filterWhen(event -> hasPermission(event.getId(), userId))
            .onErrorResume(error -> {
                System.err.println("Elasticsearch search failed: " + error.getMessage());
                return Flux.empty();
            });
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
    
    public Flux<Event> streamSearchResults(String query, String userId) {
        return searchEventsForUser(query, userId)
            .take(100); // Limit results for streaming
    }
}