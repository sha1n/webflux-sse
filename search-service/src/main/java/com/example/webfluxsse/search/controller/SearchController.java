package com.example.webfluxsse.search.controller;

import com.example.webfluxsse.search.api.dto.SearchRequest;
import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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
                      "Uses RPC-style POST endpoint with request body for search parameters."
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
    public Flux<Event> search(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Search request parameters",
            required = true,
            content = @Content(schema = @Schema(implementation = SearchRequest.class))
        )
        @org.springframework.web.bind.annotation.RequestBody SearchRequest request
    ) {
        return searchService.searchEventsForUser(request.query(), request.userId());
    }

}
