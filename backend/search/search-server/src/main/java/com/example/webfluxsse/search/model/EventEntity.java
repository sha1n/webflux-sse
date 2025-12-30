package com.example.webfluxsse.search.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Event database entity - internal persistence model. Used for both R2DBC (PostgreSQL) and
 * Elasticsearch persistence.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("events")
@Document(indexName = "events")
public class EventEntity {
  @Id private Long id;

  @Field(
      type = FieldType.Date,
      format = {},
      pattern = "uuuu-MM-dd'T'HH:mm:ss")
  private LocalDateTime timestamp;

  @Field(type = FieldType.Text, analyzer = "standard")
  private String title;

  @Field(type = FieldType.Text, analyzer = "standard")
  private String description;

  /** Constructor for creating new events without an ID. */
  public EventEntity(LocalDateTime timestamp, String title, String description) {
    this.timestamp = timestamp;
    this.title = title;
    this.description = description;
  }
}
