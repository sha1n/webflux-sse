package com.example.webfluxsse;

import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.model.UserEventPermission;
import com.example.webfluxsse.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.repository.r2dbc.EventRepository;
import com.example.webfluxsse.repository.r2dbc.UserEventPermissionRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Search POC Integration Tests with PostgreSQL and Elasticsearch Testcontainers")
class SearchPOCIntegrationIT {

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
    private UserEventPermissionRepository permissionRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void setUp() {
        // Clean up data
        elasticsearchRepository.deleteAll().block();
        permissionRepository.deleteAll().block();
        eventRepository.deleteAll().block();
    }

    @Test
    @DisplayName("Should create event and make it searchable")
    void shouldCreateEventAndMakeItSearchable() throws InterruptedException {
        // Create an event via API
        String requestBody = """
            {
                "title": "Test Event",
                "description": "This is test content for searching"
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

        // Wait for Elasticsearch indexing
        Thread.sleep(2000);

        // Grant permission to user1
        String permissionRequest = String.format("""
            {
                "eventId": %d,
                "userId": "user1"
            }
            """, eventId);

        webTestClient.post()
                .uri("/api/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(permissionRequest)
                .exchange()
                .expectStatus().isCreated();

        // Search for the event as user1
        webTestClient.get()
                .uri("/api/search?q=test")
                .header("X-User-Id", "user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(1)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).getTitle().equals("Test Event");
                });
    }

    @Test
    @DisplayName("Should filter search results based on user permissions")
    void shouldFilterSearchResultsBasedOnUserPermissions() throws InterruptedException {
        // Create two events
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Public Event", "Available to user1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Private Event", "Available to user2")).block();

        // Index in Elasticsearch
        elasticsearchRepository.save(event1).block();
        elasticsearchRepository.save(event2).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Grant permissions
        permissionRepository.save(new UserEventPermission(event1.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event2.getId(), "user2")).block();

        // Search as user1 - should only see event1
        webTestClient.get()
                .uri("/api/search?q=Event")
                .header("X-User-Id", "user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(1)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).getTitle().equals("Public Event");
                });

        // Search as user2 - should only see event2
        webTestClient.get()
                .uri("/api/search?q=Event")
                .header("X-User-Id", "user2")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(1)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).getTitle().equals("Private Event");
                });
    }

    @Test
    @DisplayName("Should return empty results for user with no permissions")
    void shouldReturnEmptyResultsForUserWithNoPermissions() throws InterruptedException {
        // Create an event
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Test content")).block();
        elasticsearchRepository.save(event).block();

        // Wait for indexing  
        Thread.sleep(2000);

        // Search as user3 who has no permissions
        webTestClient.get()
                .uri("/api/search?q=Test")
                .header("X-User-Id", "user3")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Should provide streaming search results via SSE")
    void shouldProvideStreamingSearchResultsViaSSE() throws InterruptedException {
        // Create and index events
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Stream Test 1", "First event")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Stream Test 2", "Second event")).block();
        
        elasticsearchRepository.save(event1).block();
        elasticsearchRepository.save(event2).block();
        
        // Wait for indexing
        Thread.sleep(2000);

        // Grant permissions to user1
        permissionRepository.save(new UserEventPermission(event1.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event2.getId(), "user1")).block();

        // Test SSE endpoint
        webTestClient.get()
                .uri("/api/search/stream?q=Stream")
                .header("X-User-Id", "user1")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }
}