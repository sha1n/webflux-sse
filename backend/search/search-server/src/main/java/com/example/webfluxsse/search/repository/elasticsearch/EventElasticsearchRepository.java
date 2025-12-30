package com.example.webfluxsse.search.repository.elasticsearch;

import com.example.webfluxsse.search.model.EventEntity;
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository;
import reactor.core.publisher.Flux;

public interface EventElasticsearchRepository
    extends ReactiveElasticsearchRepository<EventEntity, Long> {

  Flux<EventEntity> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
      String title, String description);

  Flux<EventEntity> findByTitleContainingIgnoreCase(String title);

  Flux<EventEntity> findByDescriptionContainingIgnoreCase(String description);
}
