package com.example.webfluxsse.search.service;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.search.repository.r2dbc.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class EventsService {

    private static final Logger log = LoggerFactory.getLogger(EventsService.class);
    private final EventRepository eventRepository;
    private final EventElasticsearchRepository elasticsearchRepository;

    public EventsService(EventRepository eventRepository,
                        @Autowired(required = false) EventElasticsearchRepository elasticsearchRepository) {
        this.eventRepository = eventRepository;
        this.elasticsearchRepository = elasticsearchRepository;

        if (elasticsearchRepository != null) {
            log.info("EventsService initialized with dual persistence (PostgreSQL + Elasticsearch)");
        } else {
            log.info("EventsService initialized with single persistence (PostgreSQL only)");
        }
    }

    public Flux<Event> getAllEvents() {
        return eventRepository.findAllOrderByTimestampDesc();
    }

    public Mono<Event> saveEvent(Event event) {
        if (elasticsearchRepository != null) {
            log.info("Saving event with dual persistence: title='{}'", event.getTitle());
            return eventRepository.save(event)
                    .doOnSuccess(savedEvent -> log.info("Event saved to PostgreSQL with id={}", savedEvent.getId()))
                    .flatMap(savedEvent ->
                        elasticsearchRepository.save(savedEvent)
                                .doOnSuccess(indexedEvent -> log.info("Event indexed in Elasticsearch with id={}", indexedEvent.getId()))
                                .onErrorResume(error -> {
                                    log.error("Failed to index event in Elasticsearch: {}", error.getMessage());
                                    return Mono.just(savedEvent);
                                })
                    );
        } else {
            log.info("Saving event with single persistence: title='{}'", event.getTitle());
            return eventRepository.save(event)
                    .doOnSuccess(savedEvent -> log.info("Event saved to PostgreSQL with id={}", savedEvent.getId()));
        }
    }
}
