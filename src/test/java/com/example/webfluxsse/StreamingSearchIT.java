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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Streaming Search Integration Tests")
class StreamingSearchIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("init-test-db.sql");

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.8.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
                () -> postgres.getJdbcUrl().replace("jdbc:postgresql://", "r2dbc:postgresql://"));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getFirstMappedPort());
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventElasticsearchRepository elasticsearchRepository;

    @Autowired
    private UserEventPermissionRepository permissionRepository;

    @BeforeEach
    void setUp() {
        elasticsearchRepository.deleteAll().block();
        permissionRepository.deleteAll().block();
        eventRepository.deleteAll().block();
    }

    @Test
    @DisplayName("Should stream search results using NDJSON")
    void shouldStreamSearchResultsUsingNdjson() throws InterruptedException {
        // 1. Create and Index Events (25 events to test batching > 20)
        int totalEvents = 25;
        List<Event> events = new ArrayList<>();
        String userId = "user1";

        for (int i = 0; i < totalEvents; i++) {
            Event event = new Event(LocalDateTime.now(), "Stream Event " + i, "Content " + i);
            events.add(eventRepository.save(event).block());
        }

        // 2. Index in Elasticsearch
        elasticsearchRepository.saveAll(events).collectList().block();

        // 3. Grant Permissions for all odd numbered events (12 events)
        List<UserEventPermission> permissions = new ArrayList<>();
        for (int i = 0; i < totalEvents; i++) {
            if (i % 2 != 0) {
                permissions.add(new UserEventPermission(events.get(i).getId(), userId));
            }
        }
        permissionRepository.saveAll(permissions).collectList().block();

        // Allow ES to refresh
        Thread.sleep(2000);

        // 4. Perform Search
        Flux<Event> responseBody = webTestClient.get()
                .uri("/api/search?q=Stream")
                .header("X-User-Id", userId)
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody();

        // 5. Verify using StepVerifier
        StepVerifier.create(responseBody)
                .expectNextCount(12) // We expect 12 events (odd numbers from 0-24)
                .verifyComplete();
    }
}
