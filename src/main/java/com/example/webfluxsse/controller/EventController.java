package com.example.webfluxsse.controller;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.repository.EventRepository;
import com.example.webfluxsse.repository.EventElasticsearchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

@RestController
public class EventController {

    private final EventRepository eventRepository;
    private final EventElasticsearchRepository elasticsearchRepository;

    public EventController(EventRepository eventRepository, EventElasticsearchRepository elasticsearchRepository) {
        this.eventRepository = eventRepository;
        this.elasticsearchRepository = elasticsearchRepository;
    }

    @GetMapping(value = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Event> streamEvents() {
        return Flux.interval(Duration.ofSeconds(2))
                .flatMap(tick -> eventRepository.findAllOrderByTimestampDesc())
                .distinctUntilChanged();
    }

    @GetMapping("/api/events")
    public Flux<Event> getAllEvents() {
        return eventRepository.findAllOrderByTimestampDesc();
    }

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Event> createEvent(@RequestBody CreateEventRequest request) {
        Event event = new Event(LocalDateTime.now(), request.title(), request.description());
        return eventRepository.save(event)
                .flatMap(savedEvent -> 
                    elasticsearchRepository.save(savedEvent)
                            .onErrorResume(error -> {
                                // Log error but don't fail the request
                                System.err.println("Failed to index event in Elasticsearch: " + error.getMessage());
                                return Mono.just(savedEvent);
                            })
                );
    }

    public record CreateEventRequest(String title, String description) {}
}