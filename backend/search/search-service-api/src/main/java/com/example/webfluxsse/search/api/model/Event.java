package com.example.webfluxsse.search.api.model;

import java.time.LocalDateTime;

/**
 * Event DTO - API contract for event data exchange. This is a pure data transfer object without any
 * database annotations.
 */
public record Event(Long id, LocalDateTime timestamp, String title, String description) {
  /** Constructor for creating new events without an ID. */
  public Event(LocalDateTime timestamp, String title, String description) {
    this(null, timestamp, title, description);
  }
}
