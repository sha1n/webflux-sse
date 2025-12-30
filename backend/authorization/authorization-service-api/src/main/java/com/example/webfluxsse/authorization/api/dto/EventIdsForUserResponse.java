package com.example.webfluxsse.authorization.api.dto;

import java.util.List;

public record EventIdsForUserResponse(String userId, List<Long> eventIds) {
  public EventIdsForUserResponse {
    // Defensive copy: create immutable list to prevent external modification
    eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
  }
}
