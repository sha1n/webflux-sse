package com.example.webfluxsse.controller;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.service.SearchService;
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
    
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Event> search(@RequestParam(required = false) String q,
                             @RequestHeader("X-User-Id") String userId) {
        return searchService.searchEventsForUser(q, userId);
    }
    
}