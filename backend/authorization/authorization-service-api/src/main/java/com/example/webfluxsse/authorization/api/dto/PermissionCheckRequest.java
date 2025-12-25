package com.example.webfluxsse.authorization.api.dto;

public record PermissionCheckRequest(Long eventId, String userId) {}
