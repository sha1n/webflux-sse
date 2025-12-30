package com.example.webfluxsse.search.service;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.mapper.EventMapper;
import com.example.webfluxsse.search.model.EventEntity;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.search.repository.r2dbc.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventsService {

  private final EventRepository eventRepository;
  private final EventElasticsearchRepository elasticsearchRepository;

  public Flux<Event> getAllEvents() {
    return eventRepository.findAllOrderByTimestampDesc().map(EventMapper::toDto);
  }

  public Mono<Event> saveEvent(Event event) {
    EventEntity entity = EventMapper.toEntity(event);

    if (elasticsearchRepository != null) {
      log.info("Saving event with dual persistence: title='{}'", event.title());
      return eventRepository
          .save(entity)
          .doOnSuccess(
              savedEntity -> log.info("Event saved to PostgreSQL with id={}", savedEntity.getId()))
          .flatMap(
              savedEntity ->
                  elasticsearchRepository
                      .save(savedEntity)
                      .doOnSuccess(
                          indexedEntity ->
                              log.info(
                                  "Event indexed in Elasticsearch with id={}",
                                  indexedEntity.getId()))
                      .onErrorResume(
                          error -> {
                            log.error(
                                "Failed to index event in Elasticsearch: {}", error.getMessage());
                            return Mono.just(savedEntity);
                          }))
          .map(EventMapper::toDto);
    } else {
      log.info("Saving event with single persistence: title='{}'", event.title());
      return eventRepository
          .save(entity)
          .doOnSuccess(
              savedEntity -> log.info("Event saved to PostgreSQL with id={}", savedEntity.getId()))
          .map(EventMapper::toDto);
    }
  }
}
