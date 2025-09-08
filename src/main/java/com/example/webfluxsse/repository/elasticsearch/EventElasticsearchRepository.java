package com.example.webfluxsse.repository.elasticsearch;

import com.example.webfluxsse.model.Event;
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface EventElasticsearchRepository extends ReactiveElasticsearchRepository<Event, Long> {
    
    Flux<Event> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(String title, String description);
    
    Flux<Event> findByTitleContainingIgnoreCase(String title);
    
    Flux<Event> findByDescriptionContainingIgnoreCase(String description);
}