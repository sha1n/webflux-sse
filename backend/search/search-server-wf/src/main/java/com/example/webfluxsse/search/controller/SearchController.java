package com.example.webfluxsse.search.controller;

import com.example.search.api.dto.SearchRequest;
import com.example.search.api.model.Event;
import com.example.webfluxsse.search.service.SearchService;
import com.example.webfluxsse.search.model.UserPermissionsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@Tag(name = "Search", description = "Full-text search with permission-aware filtering")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(
        summary = "Search events with permission filtering (RPC)",
        description = "Performs full-text search across event titles and descriptions using Elasticsearch. " +
                      "Results are filtered based on user permissions via the authorization-service. " +
                      "Returns NDJSON stream (one JSON object per line) for efficient streaming. " +
                      "Uses RPC-style POST endpoint with request body for search parameters. " +
                      "Limited to 200 authorized results by default (customizable via limit parameter)."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Search completed successfully",
            content = @Content(
                mediaType = "application/x-ndjson",
                schema = @Schema(implementation = Event.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request body"
        )
    })
    @PostMapping(value = "/api/rpc/v1/search",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<Event> searchNdjson(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Search request parameters",
            required = true,
            content = @Content(schema = @Schema(implementation = SearchRequest.class))
        )
        @org.springframework.web.bind.annotation.RequestBody SearchRequest request
    ) {
        return searchService.searchEventsForUser(request.query(), request.userId(), request.limit());
    }

    @Operation(
        summary = "Search events with permission filtering (RPC) - SSE",
        description = "Performs full-text search across event titles and descriptions using Elasticsearch. " +
                      "Results are filtered based on user permissions via the authorization-service. " +
                      "Returns Server-Sent Events for real-time streaming. " +
                      "Uses RPC-style POST endpoint with request body for search parameters. " +
                      "Content negotiation: Request with Accept: text/event-stream to get this response. " +
                      "Limited to 200 authorized results by default (customizable via limit parameter)."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Search completed successfully",
            content = @Content(
                mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                schema = @Schema(implementation = Event.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request body"
        )
    })
    @PostMapping(value = "/api/rpc/v1/search",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Event>> searchSse(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Search request parameters",
            required = true,
            content = @Content(schema = @Schema(implementation = SearchRequest.class))
        )
        @org.springframework.web.bind.annotation.RequestBody SearchRequest request
    ) {
        return searchService.searchEventsForUser(request.query(), request.userId(), request.limit())
                .map(event -> ServerSentEvent.<Event>builder()
                        .data(event)
                        .build());
    }

    @Operation(
        summary = "Search events with SSE (GET)",
        description = "Performs full-text search using Server-Sent Events. " +
                      "This GET endpoint is compatible with the native EventSource API, " +
                      "allowing Chrome DevTools to display events in the EventStream tab. " +
                      "Results are filtered based on user permissions. " +
                      "Limited to 200 authorized results by default (customizable via limit query parameter)."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Search completed successfully",
            content = @Content(
                mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                schema = @Schema(implementation = Event.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid parameters"
        )
    })
    @GetMapping(value = "/api/v1/search/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Event>> searchSseGet(
        @RequestParam(required = false) String query,
        @RequestParam String userId,
        @RequestParam(required = false) Integer limit
    ) {
        return searchService.searchEventsForUser(query, userId, limit)
                .map(event -> ServerSentEvent.<Event>builder()
                        .data(event)
                        .build());
    }

    @Operation(
        summary = "Get user authorized event details",
        description = "Retrieves the count and list of event IDs that a specific user has permission to access."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved user permissions",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UserPermissionsResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid user ID provided"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found or no permissions"
        )
    })
    @GetMapping(value = "/api/v1/user-permissions/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<UserPermissionsResponse> getUserPermissions(@PathVariable String userId) {
        return searchService.getUserAuthorizedEventDetails(userId);
    }

}
