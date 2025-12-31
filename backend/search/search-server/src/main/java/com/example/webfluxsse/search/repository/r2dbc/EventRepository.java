package com.example.webfluxsse.search.repository.r2dbc;

import com.example.webfluxsse.search.model.EventEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

public interface EventRepository extends ReactiveCrudRepository<EventEntity, Long> {

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    Flux<EventEntity> findAllOrderByTimestampDesc();

    @Query("SELECT * FROM events WHERE timestamp > :since ORDER BY timestamp DESC")
    Flux<EventEntity> findByTimestampAfterOrderByTimestampDesc(LocalDateTime since);
}
