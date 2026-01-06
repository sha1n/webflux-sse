package com.example.virtualthreads.search.service;

import com.example.search.api.model.Event;
import com.example.virtualthreads.search.mapper.EventMapper;
import com.example.virtualthreads.search.model.EventEntity;
import com.example.virtualthreads.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.virtualthreads.search.repository.jpa.EventRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<Event> getAllEvents() {
        return eventRepository.findAllOrderByTimestampDesc().stream()
                .map(EventMapper::toDto)
                .collect(Collectors.toList());
    }

    public List<Event> getEventsSince(java.time.LocalDateTime since) {
        return eventRepository.findByTimestampAfterOrderByTimestampDesc(since).stream()
                .map(EventMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public Event saveEvent(Event event) {
        EventEntity entity = EventMapper.toEntity(event);

        log.info("Saving event with title='{}'", event.title());
        EventEntity savedEntity = eventRepository.save(entity);
        log.info("Event saved to PostgreSQL with id={}", savedEntity.getId());

        if (elasticsearchRepository != null) {
            try {
                elasticsearchRepository.save(savedEntity);
                log.info("Event indexed in Elasticsearch with id={}", savedEntity.getId());
            } catch (Exception e) {
                log.error("Failed to index event in Elasticsearch: {}", e.getMessage());
                // Depending on requirements, you might want to re-throw or handle this differently
            }
        }
        return EventMapper.toDto(savedEntity);
    }

    @Transactional
    public List<Event> saveEvents(List<Event> events) {
        if (events.isEmpty()) {
            log.warn("No events to save in bulk operation");
            return new ArrayList<>();
        }

        List<EventEntity> entities = events.stream()
                .map(EventMapper::toEntity)
                .collect(Collectors.toList());

        log.info("Saving {} events", entities.size());
        List<EventEntity> savedEntities = new ArrayList<>(eventRepository.saveAll(entities));
        log.info("Saved {} events to PostgreSQL", savedEntities.size());

        if (elasticsearchRepository != null) {
            try {
                elasticsearchRepository.saveAll(savedEntities);
                log.info("Completed indexing {} events in Elasticsearch", savedEntities.size());
            } catch (Exception e) {
                log.error("Failed to bulk index events in Elasticsearch: {}", e.getMessage());
            }
        }
        return savedEntities.stream()
                .map(EventMapper::toDto)
                .collect(Collectors.toList());
    }
}
