package com.example.webfluxsse.controller;

import com.example.webfluxsse.model.UserEventPermission;
import com.example.webfluxsse.repository.r2dbc.UserEventPermissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {
    
    private static final Logger log = LoggerFactory.getLogger(PermissionController.class);
    private final UserEventPermissionRepository permissionRepository;
    
    public PermissionController(UserEventPermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }
    
    @GetMapping
    public Flux<UserEventPermission> getAllPermissions() {
        return permissionRepository.findAll();
    }
    
    @GetMapping("/user/{userId}")
    public Flux<UserEventPermission> getPermissionsForUser(@PathVariable String userId) {
        return permissionRepository.findByUserId(userId);
    }
    
    @GetMapping("/event/{eventId}")
    public Flux<UserEventPermission> getPermissionsForEvent(@PathVariable Long eventId) {
        return permissionRepository.findByEventId(eventId);
    }
    
    @GetMapping("/event/{eventId}/users")
    public Mono<java.util.List<String>> getUsersForEvent(@PathVariable Long eventId) {
        return permissionRepository.findUserIdsByEventId(eventId).collectList();
    }
    
    @GetMapping("/user/{userId}/events")
    public Flux<Long> getEventsForUser(@PathVariable String userId) {
        return permissionRepository.findEventIdsByUserId(userId);
    }
    
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserEventPermission> grantPermission(@RequestBody GrantPermissionRequest request) {
        log.info("Granting permission: eventId={}, userId={}", request.eventId(), request.userId());
        UserEventPermission permission = new UserEventPermission(request.eventId(), request.userId());
        return permissionRepository.save(permission)
                .doOnSuccess(savedPermission -> log.info("Successfully granted permission with id={}", savedPermission.getId()))
                .doOnError(error -> log.error("Failed to grant permission for eventId={}, userId={}: {}", 
                    request.eventId(), request.userId(), error.getMessage()));
    }
    
    @DeleteMapping("/event/{eventId}/user/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revokePermission(@PathVariable Long eventId, @PathVariable String userId) {
        log.info("Revoking permission: eventId={}, userId={}", eventId, userId);
        return permissionRepository.deleteByEventIdAndUserId(eventId, userId)
                .doOnSuccess(v -> log.info("Successfully revoked permission for eventId={}, userId={}", eventId, userId))
                .doOnError(error -> log.error("Failed to revoke permission for eventId={}, userId={}: {}", 
                    eventId, userId, error.getMessage()));
    }
    
    @GetMapping("/check")
    public Mono<PermissionCheckResponse> checkPermission(@RequestParam Long eventId,
                                                        @RequestParam String userId) {
        log.info("Checking permission: eventId={}, userId={}", eventId, userId);
        return permissionRepository.findByEventIdAndUserId(eventId, userId)
            .hasElement()
            .map(hasPermission -> new PermissionCheckResponse(eventId, userId, hasPermission))
            .doOnNext(response -> log.info("Permission check result: eventId={}, userId={}, hasPermission={}", 
                eventId, userId, response.hasPermission()));
    }
    
    public record GrantPermissionRequest(Long eventId, String userId) {}
    public record PermissionCheckResponse(Long eventId, String userId, boolean hasPermission) {}
}