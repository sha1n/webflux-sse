package com.example.webfluxsse.authorization.api.dto;

import java.util.List;

public record BatchPermissionCheckRequest(List<Long> eventIds, String userId) {
  public BatchPermissionCheckRequest {
    // Defensive copy: create immutable list to prevent external modification
    eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
  }
}
