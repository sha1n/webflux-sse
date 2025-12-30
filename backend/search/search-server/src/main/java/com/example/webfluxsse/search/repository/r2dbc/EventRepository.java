package com.example.webfluxsse.search.repository.r2dbc;

import com.example.webfluxsse.search.model.EventEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface EventRepository extends ReactiveCrudRepository<EventEntity, Long> {

  @Query("SELECT * FROM events ORDER BY timestamp DESC")
  Flux<EventEntity> findAllOrderByTimestampDesc();
}
