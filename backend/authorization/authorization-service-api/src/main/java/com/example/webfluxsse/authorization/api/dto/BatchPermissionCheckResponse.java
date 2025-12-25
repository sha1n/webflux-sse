package com.example.webfluxsse.authorization.api.dto;

import java.util.Set;

public record BatchPermissionCheckResponse(String userId, Set<Long> authorizedEventIds) {}
