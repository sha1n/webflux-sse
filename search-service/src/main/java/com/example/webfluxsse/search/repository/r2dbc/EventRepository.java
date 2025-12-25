package com.example.webfluxsse.search.repository.r2dbc;

import com.example.webfluxsse.search.api.model.Event;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface EventRepository extends ReactiveCrudRepository<Event, Long> {

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    Flux<Event> findAllOrderByTimestampDesc();
}
