package com.example.webfluxsse.search.controller;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.service.EventsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

@RestController
@Tag(name = "Events", description = "Event management and streaming endpoints")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private final EventsService eventsService;

    public EventController(EventsService eventsService) {
        this.eventsService = eventsService;
    }

    @Operation(
        summary = "Stream events via Server-Sent Events (SSE)",
        description = "Opens a persistent connection that streams events every 2 seconds with deduplication. " +
                      "Optionally accepts a 'since' parameter to only fetch events after a specific timestamp. " +
                      "The stream automatically updates when new events are created. " +
                      "Use Accept: text/event-stream header to access this endpoint."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Event stream opened successfully",
            content = @Content(
                mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                schema = @Schema(implementation = Event.class)
            )
        )
    })
    @GetMapping(value = "/api/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Event>> streamEvents(
        @RequestParam(value = "since", required = false)
        @Schema(description = "ISO 8601 timestamp to fetch events after (e.g., 2024-01-01T10:00:00)",
                example = "2024-01-01T10:00:00")
        LocalDateTime since
    ) {
        if (since != null) {
            log.info("Starting SSE stream for events since {}", since);
        } else {
            log.info("Starting SSE stream for all events");
        }

        return Flux.interval(Duration.ofSeconds(2))
                .flatMap(tick -> {
                    Flux<ServerSentEvent<Event>> dataEvents = (since != null
                            ? eventsService.getEventsSince(since)
                            : eventsService.getAllEvents())
                            .map(event -> ServerSentEvent.builder(event).build());

                    // Always send a heartbeat with a unique comment to keep connection alive
                    // The comment contains the tick number to ensure it's not deduplicated
                    Flux<ServerSentEvent<Event>> heartbeat = Flux.just(
                            ServerSentEvent.<Event>builder()
                                    .event("heartbeat")
                                    .comment("tick-" + tick)
                                    .build()
                    );

                    // Concatenate data events with heartbeat, so heartbeat is always sent
                    return dataEvents.concatWith(heartbeat);
                })
                .doOnSubscribe(sub -> log.info("Client subscribed to event stream"))
                .doOnCancel(() -> log.info("Client cancelled event stream"));
    }

    @Operation(
        summary = "Get all events",
        description = "Returns all events ordered by timestamp descending (most recent first). " +
                      "Use Accept: application/json header to access this endpoint."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved events",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = Event.class)
            )
        )
    })
    @GetMapping(value = "/api/v1/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Event> getAllEvents() {
        log.info("Fetching all events");
        return eventsService.getAllEvents()
                .doOnComplete(() -> log.info("Successfully fetched all events"));
    }

    @Operation(
        summary = "Create a new event",
        description = "Creates a new event and persists it to Elasticsearch. The event appears immediately in the SSE stream."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201",
            description = "Event created successfully",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = Event.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request body"
        )
    })
    @PostMapping(value = "/api/v1/events",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Event> createEvent(
        @RequestBody(description = "Event to create", required = true,
                     content = @Content(schema = @Schema(implementation = CreateEventRequest.class)))
        @org.springframework.web.bind.annotation.RequestBody CreateEventRequest request
    ) {
        log.info("Creating new event: title='{}', description='{}'", request.title(), request.description());
        Event event = new Event(LocalDateTime.now(), request.title(), request.description());
        return eventsService.saveEvent(event)
                .doOnSuccess(savedEvent -> log.info("Successfully created event with id={}", savedEvent.id()))
                .doOnError(error -> log.error("Failed to create event: {}", error.getMessage()));
    }

    public record CreateEventRequest(
        @Schema(description = "Event title", example = "System Deployment", required = true)
        String title,
        @Schema(description = "Event description", example = "Application deployed to production", required = false)
        String description
    ) {}
}
