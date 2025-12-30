package com.example.webfluxsse.authorization.api.dto;

import java.util.Set;

public record BatchPermissionCheckResponse(String userId, Set<Long> authorizedEventIds) {
  public BatchPermissionCheckResponse {
    // Defensive copy: create immutable set to prevent external modification
    authorizedEventIds = authorizedEventIds == null ? Set.of() : Set.copyOf(authorizedEventIds);
  }
}
