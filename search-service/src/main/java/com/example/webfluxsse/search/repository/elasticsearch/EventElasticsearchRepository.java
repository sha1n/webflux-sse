package com.example.webfluxsse.search.repository.elasticsearch;

import com.example.webfluxsse.common.model.Event;
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository;
import reactor.core.publisher.Flux;

public interface EventElasticsearchRepository extends ReactiveElasticsearchRepository<Event, Long> {

    Flux<Event> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(String title, String description);

    Flux<Event> findByTitleContainingIgnoreCase(String title);

    Flux<Event> findByDescriptionContainingIgnoreCase(String description);
}
