package com.example.webfluxsse.search.controller;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.service.EventsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private final EventsService eventsService;

    public EventController(EventsService eventsService) {
        this.eventsService = eventsService;
    }

    @GetMapping(value = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Event> streamEvents() {
        log.info("Starting SSE stream for events");
        return Flux.interval(Duration.ofSeconds(2))
                .flatMap(tick -> eventsService.getAllEvents())
                .distinctUntilChanged()
                .doOnSubscribe(sub -> log.info("Client subscribed to event stream"))
                .doOnCancel(() -> log.info("Client cancelled event stream"));
    }

    @GetMapping("/api/events")
    public Flux<Event> getAllEvents() {
        log.info("Fetching all events");
        return eventsService.getAllEvents()
                .doOnComplete(() -> log.info("Successfully fetched all events"));
    }

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Event> createEvent(@RequestBody CreateEventRequest request) {
        log.info("Creating new event: title='{}', description='{}'", request.title(), request.description());
        Event event = new Event(LocalDateTime.now(), request.title(), request.description());
        return eventsService.saveEvent(event)
                .doOnSuccess(savedEvent -> log.info("Successfully created event with id={}", savedEvent.getId()))
                .doOnError(error -> log.error("Failed to create event: {}", error.getMessage()));
    }

    public record CreateEventRequest(String title, String description) {}
}
