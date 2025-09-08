package com.example.webfluxsse.controller;

import com.example.webfluxsse.model.UserEventPermission;
import com.example.webfluxsse.repository.r2dbc.UserEventPermissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {
    
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
    public Flux<String> getUsersForEvent(@PathVariable Long eventId) {
        return permissionRepository.findUserIdsByEventId(eventId);
    }
    
    @GetMapping("/user/{userId}/events")
    public Flux<Long> getEventsForUser(@PathVariable String userId) {
        return permissionRepository.findEventIdsByUserId(userId);
    }
    
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserEventPermission> grantPermission(@RequestBody GrantPermissionRequest request) {
        UserEventPermission permission = new UserEventPermission(request.eventId(), request.userId());
        return permissionRepository.save(permission);
    }
    
    @DeleteMapping("/event/{eventId}/user/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revokePermission(@PathVariable Long eventId, @PathVariable String userId) {
        return permissionRepository.deleteByEventIdAndUserId(eventId, userId);
    }
    
    @GetMapping("/check")
    public Mono<PermissionCheckResponse> checkPermission(@RequestParam Long eventId,
                                                        @RequestParam String userId) {
        return permissionRepository.findByEventIdAndUserId(eventId, userId)
            .hasElement()
            .map(hasPermission -> new PermissionCheckResponse(eventId, userId, hasPermission));
    }
    
    public record GrantPermissionRequest(Long eventId, String userId) {}
    public record PermissionCheckResponse(Long eventId, String userId, boolean hasPermission) {}
}