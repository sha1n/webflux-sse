package com.example.webfluxsse.authorization.controller;

import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckRequest;
import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import com.example.webfluxsse.authorization.api.model.UserEventPermission;
import com.example.webfluxsse.authorization.mapper.PermissionMapper;
import com.example.webfluxsse.authorization.model.UserEventPermissionEntity;
import com.example.webfluxsse.authorization.repository.UserEventPermissionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/permissions")
@Tag(name = "Permissions", description = "User-event permission management")
public class PermissionController {

    private static final Logger log = LoggerFactory.getLogger(PermissionController.class);
    private final UserEventPermissionRepository permissionRepository;

    public PermissionController(UserEventPermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @Operation(summary = "Get all permissions", description = "Returns all user-event permission mappings")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved all permissions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UserEventPermission.class)))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<UserEventPermission> getAllPermissions() {
        return permissionRepository.findAll()
                .map(PermissionMapper::toDto);
    }

    @Operation(summary = "Get permissions for a user", description = "Returns all permissions granted to a specific user")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved user permissions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UserEventPermission.class)))
    })
    @GetMapping(value = "/user/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<UserEventPermission> getPermissionsForUser(
        @Parameter(description = "User identifier", example = "user123", required = true)
        @PathVariable String userId
    ) {
        return permissionRepository.findByUserId(userId)
                .map(PermissionMapper::toDto);
    }

    @Operation(summary = "Get permissions for an event", description = "Returns all users who have permission to access a specific event")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved event permissions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UserEventPermission.class)))
    })
    @GetMapping(value = "/event/{eventId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<UserEventPermission> getPermissionsForEvent(
        @Parameter(description = "Event identifier", example = "42", required = true)
        @PathVariable Long eventId
    ) {
        return permissionRepository.findByEventId(eventId)
                .map(PermissionMapper::toDto);
    }

    @Operation(summary = "Get users for an event", description = "Returns list of user IDs who have permission to access a specific event")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved user IDs",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(type = "array", implementation = String.class)))
    })
    @GetMapping(value = "/event/{eventId}/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<java.util.List<String>> getUsersForEvent(
        @Parameter(description = "Event identifier", example = "42", required = true)
        @PathVariable Long eventId
    ) {
        return permissionRepository.findUserIdsByEventId(eventId).collectList();
    }

    @Operation(summary = "Get events for a user", description = "Returns list of event IDs that a user has permission to access")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved event IDs",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(type = "array", implementation = Long.class)))
    })
    @GetMapping(value = "/user/{userId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Long> getEventsForUser(
        @Parameter(description = "User identifier", example = "user123", required = true)
        @PathVariable String userId
    ) {
        return permissionRepository.findEventIdsByUserId(userId);
    }

    @Operation(summary = "Grant permission", description = "Grants a user permission to access a specific event")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Permission granted successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UserEventPermission.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body"),
        @ApiResponse(responseCode = "409", description = "Permission already exists")
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserEventPermission> grantPermission(
        @RequestBody(description = "Permission to grant", required = true,
            content = @Content(schema = @Schema(implementation = GrantPermissionRequest.class)))
        @org.springframework.web.bind.annotation.RequestBody GrantPermissionRequest request
    ) {
        log.info("Granting permission: eventId={}, userId={}", request.eventId(), request.userId());
        UserEventPermissionEntity entity = new UserEventPermissionEntity(request.eventId(), request.userId());
        return permissionRepository.save(entity)
                .map(PermissionMapper::toDto)
                .doOnSuccess(savedPermission -> log.info("Successfully granted permission with id={}", savedPermission.id()))
                .doOnError(error -> log.error("Failed to grant permission for eventId={}, userId={}: {}",
                    request.eventId(), request.userId(), error.getMessage()));
    }

    @Operation(summary = "Revoke permission", description = "Revokes a user's permission to access a specific event")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Permission revoked successfully"),
        @ApiResponse(responseCode = "404", description = "Permission not found")
    })
    @DeleteMapping("/event/{eventId}/user/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revokePermission(
        @Parameter(description = "Event identifier", example = "42", required = true)
        @PathVariable Long eventId,
        @Parameter(description = "User identifier", example = "user123", required = true)
        @PathVariable String userId
    ) {
        log.info("Revoking permission: eventId={}, userId={}", eventId, userId);
        return permissionRepository.deleteByEventIdAndUserId(eventId, userId)
                .doOnSuccess(v -> log.info("Successfully revoked permission for eventId={}, userId={}", eventId, userId))
                .doOnError(error -> log.error("Failed to revoke permission for eventId={}, userId={}: {}",
                    eventId, userId, error.getMessage()));
    }

    @Operation(summary = "Check permission", description = "Checks if a user has permission to access a specific event")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Permission check completed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = PermissionCheckResponse.class)))
    })
    @GetMapping(value = "/check", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<PermissionCheckResponse> checkPermission(
        @Parameter(description = "Event identifier", example = "42", required = true)
        @RequestParam Long eventId,
        @Parameter(description = "User identifier", example = "user123", required = true)
        @RequestParam String userId
    ) {
        log.info("Checking permission: eventId={}, userId={}", eventId, userId);
        return permissionRepository.findByEventIdAndUserId(eventId, userId)
            .hasElement()
            .map(hasPermission -> new PermissionCheckResponse(eventId, userId, hasPermission))
            .doOnNext(response -> log.info("Permission check result: eventId={}, userId={}, hasPermission={}",
                eventId, userId, response.hasPermission()));
    }

    @Operation(
        summary = "Batch check permissions (Internal API)",
        description = "Checks which events from a list a user has permission to access. " +
                      "Used by search-service for efficient permission filtering. " +
                      "Returns only the event IDs the user is authorized to access."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Batch check completed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = BatchPermissionCheckResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping(value = "/batch-check",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<BatchPermissionCheckResponse> batchCheckPermissions(
        @RequestBody(description = "Batch permission check request", required = true,
            content = @Content(schema = @Schema(implementation = BatchPermissionCheckRequest.class)))
        @org.springframework.web.bind.annotation.RequestBody BatchPermissionCheckRequest request
    ) {
        log.info("Batch checking permissions: eventIds={}, userId={}", request.eventIds(), request.userId());

        return permissionRepository.findAllByUserIdAndEventIdIn(request.userId(), request.eventIds())
                .map(UserEventPermissionEntity::getEventId)
                .collect(java.util.stream.Collectors.toSet())
                .map(authorizedIds -> new BatchPermissionCheckResponse(request.userId(), authorizedIds))
                .doOnNext(response -> log.info("Batch check result: userId={}, authorizedCount={}",
                    request.userId(), response.authorizedEventIds().size()));
    }

    @Operation(
        summary = "Grant multiple permissions (Bulk)",
        description = "Grants a user permission to access multiple events in a single request. " +
                      "Skips events that already have permissions granted (no error on duplicates)."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Permissions granted successfully (returns only newly created permissions)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UserEventPermission.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping(value = "/bulk",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Flux<UserEventPermission> grantMultiplePermissions(
        @RequestBody(description = "Bulk permission grant request", required = true,
            content = @Content(schema = @Schema(implementation = GrantMultiplePermissionsRequest.class)))
        @org.springframework.web.bind.annotation.RequestBody GrantMultiplePermissionsRequest request
    ) {
        log.info("Granting permissions for multiple events: eventIds={}, userId={}",
            request.eventIds(), request.userId());

        return Flux.fromIterable(request.eventIds())
                .map(eventId -> new UserEventPermissionEntity(eventId, request.userId()))
                .flatMap(entity ->
                    permissionRepository.save(entity)
                        .map(PermissionMapper::toDto)
                        .onErrorResume(error -> {
                            log.warn("Permission already exists for eventId={}, userId={}, skipping",
                                entity.getEventId(), entity.getUserId());
                            return Mono.empty(); // Skip duplicate permissions
                        })
                )
                .doOnNext(savedPermission -> log.info("Successfully granted permission with id={}", savedPermission.id()))
                .doOnComplete(() -> log.info("Completed bulk permission grant for userId={}", request.userId()));
    }

    public record GrantPermissionRequest(
        @Schema(description = "Event identifier", example = "42", required = true)
        Long eventId,
        @Schema(description = "User identifier", example = "user123", required = true)
        String userId
    ) {}

    public record GrantMultiplePermissionsRequest(
        @Schema(description = "List of event identifiers", example = "[1, 2, 3, 4, 5]", required = true)
        java.util.List<Long> eventIds,
        @Schema(description = "User identifier", example = "user123", required = true)
        String userId
    ) {}

    public record PermissionCheckResponse(
        @Schema(description = "Event identifier", example = "42")
        Long eventId,
        @Schema(description = "User identifier", example = "user123")
        String userId,
        @Schema(description = "Whether the user has permission", example = "true")
        boolean hasPermission
    ) {}
}
