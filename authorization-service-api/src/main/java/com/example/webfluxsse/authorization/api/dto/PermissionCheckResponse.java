package com.example.webfluxsse.authorization.api.dto;

public record PermissionCheckResponse(Long eventId, String userId, boolean hasPermission) {}
