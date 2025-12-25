package com.example.webfluxsse;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.repository.r2dbc.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Dual Persistence Integration Tests")
class DualPersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("init-test-db.sql");

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.8.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL configuration
        registry.add("spring.r2dbc.url", () -> {
            String jdbcUrl = postgres.getJdbcUrl();
            return jdbcUrl.replace("jdbc:postgresql://", "r2dbc:postgresql://");
        });
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        
        // Elasticsearch configuration
        registry.add("spring.elasticsearch.uris", () -> 
            "http://" + elasticsearch.getHost() + ":" + elasticsearch.getFirstMappedPort());
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventElasticsearchRepository elasticsearchRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void setUp() {
        // Clean up data
        elasticsearchRepository.deleteAll().block();
        eventRepository.deleteAll().block();
    }

    @Test
    @DisplayName("Should create event in both PostgreSQL and Elasticsearch")
    void shouldCreateEventInBothPersistenceStores() throws InterruptedException {
        // Create an event via API
        String requestBody = """
            {
                "title": "Test Event",
                "description": "This is a test event for dual persistence"
            }
            """;

        Long eventId = webTestClient.post()
                .uri("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Event.class)
                .returnResult()
                .getResponseBody()
                .getId();

        // Verify event exists in PostgreSQL
        Event postgresEvent = eventRepository.findById(eventId).block();
        assert postgresEvent != null;
        assert postgresEvent.getTitle().equals("Test Event");
        assert postgresEvent.getDescription().equals("This is a test event for dual persistence");

        // Wait for Elasticsearch indexing
        Thread.sleep(2000);

        // Verify event exists in Elasticsearch
        Event elasticsearchEvent = elasticsearchRepository.findById(eventId).block();
        assert elasticsearchEvent != null;
        assert elasticsearchEvent.getTitle().equals("Test Event");
        assert elasticsearchEvent.getDescription().equals("This is a test event for dual persistence");
    }

    @Test
    @DisplayName("Should verify EventElasticsearchRepository is created")
    void shouldVerifyEventElasticsearchRepositoryIsCreated() {
        assert elasticsearchRepository != null;
        assert eventRepository != null;
    }
}