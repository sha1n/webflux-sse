package com.example.webfluxsse.common.dto;

public record PermissionCheckResponse(Long eventId, String userId, boolean hasPermission) {}
