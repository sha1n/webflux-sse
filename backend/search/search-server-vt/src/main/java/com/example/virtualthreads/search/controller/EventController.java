package com.example.virtualthreads.search.controller;

import com.example.search.api.model.Event;
import com.example.virtualthreads.search.service.EventsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@Tag(name = "Events", description = "Event management and streaming endpoints")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private final EventsService eventsService;
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();


    public EventController(EventsService eventsService) {
        this.eventsService = eventsService;
    }

    @Operation(
            summary = "Stream events via Server-Sent Events (SSE)",
            description = "Opens a persistent connection that streams events every 2 seconds."
    )
    @GetMapping(value = "/api/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @RequestParam(value = "since", required = false) LocalDateTime since
    ) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        sseExecutor.execute(() -> {
            try {
                int tick = 0;
                while (true) {
                    SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event();
                    List<Event> events = (since != null)
                            ? eventsService.getEventsSince(since)
                            : eventsService.getAllEvents();

                    for (Event event : events) {
                        emitter.send(eventBuilder.data(event));
                    }

                    // Send heartbeat
                    emitter.send(SseEmitter.event().name("heartbeat").comment("tick-" + tick++));

                    Thread.sleep(2000);
                }
            } catch (IOException | InterruptedException e) {
                log.info("Client disconnected from SSE stream.");
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping(value = "/api/v1/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Event> getAllEvents() {
        log.info("Fetching all events");
        return eventsService.getAllEvents();
    }

    @PostMapping(value = "/api/v1/events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Event createEvent(@RequestBody CreateEventRequest request) {
        log.info("Creating new event: title='{}'", request.title());
        Event event = new Event(null, LocalDateTime.now(), request.title(), request.description());
        return eventsService.saveEvent(event);
    }

    @PostMapping(value = "/api/v1/events/bulk",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<Event> createEventsBulk(@RequestBody BulkCreateEventsRequest request) {
        log.info("Creating {} events in bulk", request.events().size());
        List<Event> events = request.events().stream()
                .map(req -> new Event(null, LocalDateTime.now(), req.title(), req.description()))
                .collect(Collectors.toList());
        return eventsService.saveEvents(events);
    }

    public record CreateEventRequest(String title, String description) {}
    public record BulkCreateEventsRequest(List<CreateEventRequest> events) {}
}
