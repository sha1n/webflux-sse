package com.example.webfluxsse.service;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.repository.r2dbc.EventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class DualPersistenceService {
    
    private final EventRepository eventRepository;
    private final EventElasticsearchRepository elasticsearchRepository;
    
    public DualPersistenceService(EventRepository eventRepository,
                                EventElasticsearchRepository elasticsearchRepository) {
        this.eventRepository = eventRepository;
        this.elasticsearchRepository = elasticsearchRepository;
    }
    
    public Mono<Event> saveEvent(Event event) {
        return eventRepository.save(event)
                .flatMap(savedEvent -> 
                    elasticsearchRepository.save(savedEvent)
                            .onErrorResume(error -> {
                                System.err.println("Failed to index event in Elasticsearch: " + error.getMessage());
                                return Mono.just(savedEvent);
                            })
                );
    }
}