package com.example.webfluxsse.search.service;

import com.example.search.api.model.Event;
import com.example.webfluxsse.search.mapper.EventMapper;
import com.example.webfluxsse.search.model.EventEntity;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.search.repository.r2dbc.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class EventsService {

    private static final Logger log = LoggerFactory.getLogger(EventsService.class);
    private final EventRepository eventRepository;
    private final EventElasticsearchRepository elasticsearchRepository;

    public EventsService(EventRepository eventRepository,
                        EventElasticsearchRepository elasticsearchRepository) {
        this.eventRepository = eventRepository;
        this.elasticsearchRepository = elasticsearchRepository;

        if (elasticsearchRepository != null) {
            log.info("EventsService initialized with dual persistence (PostgreSQL + Elasticsearch)");
        } else {
            log.info("EventsService initialized with single persistence (PostgreSQL only)");
        }
    }

    public Flux<Event> getAllEvents() {
        return eventRepository.findAllOrderByTimestampDesc()
                .map(EventMapper::toDto);
    }

    public Flux<Event> getEventsSince(java.time.LocalDateTime since) {
        return eventRepository.findByTimestampAfterOrderByTimestampDesc(since)
                .map(EventMapper::toDto);
    }

    public Mono<Event> saveEvent(Event event) {
        EventEntity entity = EventMapper.toEntity(event);

        if (elasticsearchRepository != null) {
            log.info("Saving event with dual persistence: title='{}'", event.title());
            return eventRepository.save(entity)
                    .doOnSuccess(savedEntity -> log.info("Event saved to PostgreSQL with id={}", savedEntity.getId()))
                    .flatMap(savedEntity ->
                        elasticsearchRepository.save(savedEntity)
                                .doOnSuccess(indexedEntity -> log.info("Event indexed in Elasticsearch with id={}", indexedEntity.getId()))
                                .onErrorResume(error -> {
                                    log.error("Failed to index event in Elasticsearch: {}", error.getMessage());
                                    return Mono.just(savedEntity);
                                })
                    )
                    .map(EventMapper::toDto);
        } else {
            log.info("Saving event with single persistence: title='{}'", event.title());
            return eventRepository.save(entity)
                    .doOnSuccess(savedEntity -> log.info("Event saved to PostgreSQL with id={}", savedEntity.getId()))
                    .map(EventMapper::toDto);
        }
    }

    public Flux<Event> saveEvents(Flux<Event> events) {
        return events
                .map(EventMapper::toEntity)
                .collectList()
                .flatMapMany(entities -> {
                    if (entities.isEmpty()) {
                        log.warn("No events to save in bulk operation");
                        return Flux.empty();
                    }

                    if (elasticsearchRepository != null) {
                        log.info("Saving {} events with dual persistence", entities.size());
                        return Flux.fromIterable(entities)
                                .flatMap(eventRepository::save)
                                .collectList()
                                .doOnSuccess(savedEntities -> log.info("Saved {} events to PostgreSQL", savedEntities.size()))
                                .flatMapMany(savedEntities ->
                                    Flux.fromIterable(savedEntities)
                                            .flatMap(entity ->
                                                elasticsearchRepository.save(entity)
                                                        .onErrorResume(error -> {
                                                            log.error("Failed to index event {} in Elasticsearch: {}",
                                                                    entity.getId(), error.getMessage());
                                                            return Mono.just(entity);
                                                        })
                                            )
                                )
                                .doOnComplete(() -> log.info("Completed indexing events in Elasticsearch"))
                                .map(EventMapper::toDto);
                    } else {
                        log.info("Saving {} events with single persistence", entities.size());
                        return Flux.fromIterable(entities)
                                .flatMap(eventRepository::save)
                                .doOnComplete(() -> log.info("Saved {} events to PostgreSQL", entities.size()))
                                .map(EventMapper::toDto);
                    }
                });
    }
}
