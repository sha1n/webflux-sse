package com.example.virtualthreads.search.controller;

import com.example.search.api.dto.SearchRequest;
import com.example.virtualthreads.search.service.SearchService;
import com.example.virtualthreads.search.model.UserPermissionsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@Tag(name = "Search", description = "Full-text search with permission-aware filtering")
public class SearchController {

    private final SearchService searchService;
    private final ObjectMapper objectMapper;
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SearchController(SearchService searchService, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/api/rpc/v1/search/ndjson", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> searchNdjson(@RequestBody SearchRequest request) {
        StreamingResponseBody stream = out -> {
            try (var eventStream = searchService.searchEventsForUser(request.query(), request.userId(), request.limit())) {
                eventStream.forEach(event -> {
                    try {
                        out.write(objectMapper.writeValueAsBytes(event));
                        out.write('\n');
                    } catch (IOException e) {
                        throw new RuntimeException("Error writing event to stream", e);
                    }
                });
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(stream);
    }

    @PostMapping(value = "/api/rpc/v1/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter searchSse(@RequestBody SearchRequest request) {
        return createSseEmitter(request.query(), request.userId(), request.limit());
    }

    @GetMapping(value = "/api/v1/search/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter searchSseGet(
            @RequestParam(required = false) String query,
            @RequestParam String userId,
            @RequestParam(required = false) Integer limit) {
        return createSseEmitter(query, userId, limit);
    }

    private SseEmitter createSseEmitter(String query, String userId, Integer limit) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        sseExecutor.execute(() -> {
            try (var eventStream = searchService.searchEventsForUser(query, userId, limit)) {
                // Backpressure mechanism: limit in-flight events to prevent memory bloat
                final int maxInFlight = 10;
                final AtomicInteger inFlight = new AtomicInteger(0);
                final Object lock = new Object();

                eventStream.forEach(event -> {
                    // Wait if too many events are in-flight (slow client)
                    synchronized (lock) { // TODO shai: IMPORTANT this should be questioned and possibly tuned!!!
                        while (inFlight.get() >= maxInFlight) {
                            try {
                                lock.wait(100); // Wait up to 100ms for client to consume events
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Interrupted while waiting for backpressure", e);
                            }
                        }
                        inFlight.incrementAndGet();
                    }

                    // Send event to client
                    try {
                        emitter.send(SseEmitter.event().data(event));
                    } catch (IOException e) {
                        throw new RuntimeException("Error sending event", e);
                    } finally {
                        // Decrement in-flight counter and notify waiting threads
                        synchronized (lock) {
                            inFlight.decrementAndGet();
                            lock.notifyAll();
                        }
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping(value = "/api/v1/user-permissions/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserPermissionsResponse getUserPermissions(@PathVariable String userId) {
        return searchService.getUserAuthorizedEventDetails(userId);
    }
}
