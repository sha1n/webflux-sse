package com.example.webfluxsse.authorization.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * UserEventPermission database entity - internal persistence model. Used for R2DBC (PostgreSQL)
 * persistence.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_event_permissions")
public class UserEventPermissionEntity {

  @Id private Long id;
  private Long eventId;
  private String userId;

  /** Constructor for creating new permissions without an ID. */
  public UserEventPermissionEntity(Long eventId, String userId) {
    this.eventId = eventId;
    this.userId = userId;
  }
}
