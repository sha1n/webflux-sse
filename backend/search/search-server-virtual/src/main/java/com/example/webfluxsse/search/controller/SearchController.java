package com.example.webfluxsse.search.controller;

import com.example.webfluxsse.search.api.dto.SearchRequest;
import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
                eventStream.forEach(event -> {
                    try {
                        emitter.send(SseEmitter.event().data(event));
                    } catch (IOException e) {
                        throw new RuntimeException("Error sending event", e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
