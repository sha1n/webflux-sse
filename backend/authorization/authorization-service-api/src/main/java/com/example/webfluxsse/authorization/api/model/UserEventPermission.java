package com.example.webfluxsse.authorization.api.model;

/**
 * UserEventPermission DTO - API contract for permission data exchange. This is a pure data transfer
 * object without any database annotations.
 */
public record UserEventPermission(Long id, Long eventId, String userId) {
  /** Constructor for creating new permissions without an ID. */
  public UserEventPermission(Long eventId, String userId) {
    this(null, eventId, userId);
  }
}
