package com.example.webfluxsse.search.controller;

import com.example.webfluxsse.common.model.Event;
import com.example.webfluxsse.search.service.SearchService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping(produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<Event> search(@RequestParam(required = false) String q,
            @RequestHeader("X-User-Id") String userId) {
        return searchService.searchEventsForUser(q, userId);
    }

}
