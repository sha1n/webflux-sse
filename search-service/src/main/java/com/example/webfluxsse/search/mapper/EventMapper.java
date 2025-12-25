package com.example.webfluxsse.search.mapper;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.model.EventEntity;

/**
 * Mapper utility to convert between Event DTO and EventEntity.
 */
public class EventMapper {

    private EventMapper() {
        // Utility class
    }

    public static Event toDto(EventEntity entity) {
        if (entity == null) {
            return null;
        }
        return new Event(
                entity.getId(),
                entity.getTimestamp(),
                entity.getTitle(),
                entity.getDescription()
        );
    }

    public static EventEntity toEntity(Event dto) {
        if (dto == null) {
            return null;
        }
        EventEntity entity = new EventEntity(
                dto.timestamp(),
                dto.title(),
                dto.description()
        );
        if (dto.id() != null) {
            entity.setId(dto.id());
        }
        return entity;
    }
}
