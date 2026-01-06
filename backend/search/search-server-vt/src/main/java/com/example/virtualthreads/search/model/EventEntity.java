package com.example.virtualthreads.search.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

/**
 * Event database entity - internal persistence model.
 * Used for both JPA (PostgreSQL) and Elasticsearch persistence.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "events")
@Document(indexName = "events")
public class EventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Field(type = FieldType.Date, format = {}, pattern = "uuuu-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String title;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String description;

    /**
     * Constructor for creating new events without an ID.
     */
    public EventEntity(LocalDateTime timestamp, String title, String description) {
        this.timestamp = timestamp;
        this.title = title;
        this.description = description;
    }
}
