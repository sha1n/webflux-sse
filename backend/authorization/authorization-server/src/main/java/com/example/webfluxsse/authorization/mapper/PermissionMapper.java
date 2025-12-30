package com.example.webfluxsse.authorization.mapper;

import com.example.webfluxsse.authorization.api.model.UserEventPermission;
import com.example.webfluxsse.authorization.model.UserEventPermissionEntity;

/** Mapper utility to convert between UserEventPermission DTO and UserEventPermissionEntity. */
public class PermissionMapper {

  private PermissionMapper() {
    // Utility class
  }

  public static UserEventPermission toDto(UserEventPermissionEntity entity) {
    if (entity == null) {
      return null;
    }
    return new UserEventPermission(entity.getId(), entity.getEventId(), entity.getUserId());
  }

  public static UserEventPermissionEntity toEntity(UserEventPermission dto) {
    if (dto == null) {
      return null;
    }
    UserEventPermissionEntity entity = new UserEventPermissionEntity(dto.eventId(), dto.userId());
    if (dto.id() != null) {
      entity.setId(dto.id());
    }
    return entity;
  }
}
