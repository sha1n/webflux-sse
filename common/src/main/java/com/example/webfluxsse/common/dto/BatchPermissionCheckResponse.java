package com.example.webfluxsse.common.dto;

import java.util.Set;

public record BatchPermissionCheckResponse(String userId, Set<Long> authorizedEventIds) {}
