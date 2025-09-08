package com.example.webfluxsse.controller;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.repository.r2dbc.EventRepository;
import com.example.webfluxsse.service.DualPersistenceService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final DualPersistenceService dualPersistenceService;

    public EventController(EventRepository eventRepository, 
                          @Autowired(required = false) DualPersistenceService dualPersistenceService) {
        this.eventRepository = eventRepository;
        this.dualPersistenceService = dualPersistenceService;
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
        
        if (dualPersistenceService != null) {
            return dualPersistenceService.saveEvent(event);
        } else {
            return eventRepository.save(event);
        }
    }

    public record CreateEventRequest(String title, String description) {}
}