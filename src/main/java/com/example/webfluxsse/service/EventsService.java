package com.example.webfluxsse.service;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.repository.r2dbc.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class EventsService {
    
    private final EventRepository eventRepository;
    private final EventElasticsearchRepository elasticsearchRepository;
    
    public EventsService(EventRepository eventRepository,
                        @Autowired(required = false) EventElasticsearchRepository elasticsearchRepository) {
        this.eventRepository = eventRepository;
        this.elasticsearchRepository = elasticsearchRepository;
    }
    
    public Flux<Event> getAllEvents() {
        return eventRepository.findAllOrderByTimestampDesc();
    }
    
    public Mono<Event> saveEvent(Event event) {
        if (elasticsearchRepository != null) {
            return eventRepository.save(event)
                    .flatMap(savedEvent -> 
                        elasticsearchRepository.save(savedEvent)
                                .onErrorResume(error -> {
                                    System.err.println("Failed to index event in Elasticsearch: " + error.getMessage());
                                    return Mono.just(savedEvent);
                                })
                    );
        } else {
            return eventRepository.save(event);
        }
    }
}