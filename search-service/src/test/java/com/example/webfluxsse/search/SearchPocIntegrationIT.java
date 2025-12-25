package com.example.webfluxsse.search;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.search.repository.r2dbc.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Search POC Integration Tests with PostgreSQL and Elasticsearch Testcontainers")
class SearchPocIntegrationIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("init-test-db.sql");

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.8.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withStartupTimeout(Duration.ofMinutes(2));

    private static WireMockServer wireMockServer;

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

        // Configure WireMock server for authorization-service with dynamic port
        if (wireMockServer == null) {
            wireMockServer = new WireMockServer(0); // Use dynamic port
            wireMockServer.start();
        }
        WireMock.configureFor("localhost", wireMockServer.port());
        registry.add("authorization-service.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventElasticsearchRepository elasticsearchRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Clean up data
        elasticsearchRepository.deleteAll().block();
        eventRepository.deleteAll().block();
        wireMockServer.resetAll();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("Should create event and make it searchable")
    void shouldCreateEventAndMakeItSearchable() throws Exception {
        // Create an event via API
        String requestBody = """
            {
                "title": "Test Event",
                "description": "This is test content for searching"
            }
            """;

        Event createdEvent = webTestClient.post()
                .uri("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Event.class)
                .returnResult()
                .getResponseBody();

        Long eventId = createdEvent.getId();

        // Wait for Elasticsearch indexing
        Thread.sleep(2000);

        // Mock the authorization-service batch-check endpoint to return this event as authorized for user1
        stubFor(post(urlEqualTo("/api/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user1")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user1",
                                "authorizedEventIds", Set.of(eventId)
                        )))));

        // Search for the event as user1
        Flux<Event> responseBody = webTestClient.get()
                .uri("/api/search?q=test")
                .header("X-User-Id", "user1")
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(responseBody)
                .expectNextMatches(event -> event.getTitle().equals("Test Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should filter search results based on user permissions")
    void shouldFilterSearchResultsBasedOnUserPermissions() throws Exception {
        // Create two events
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Public Event", "Available to user1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Private Event", "Available to user2")).block();

        // Index in Elasticsearch
        elasticsearchRepository.save(event1).block();
        elasticsearchRepository.save(event2).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Mock the authorization-service batch-check endpoint for user1 (only has access to event1)
        stubFor(post(urlEqualTo("/api/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user1")))
                .withRequestBody(matchingJsonPath("$.eventIds[?(@ == " + event1.getId() + ")]"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user1",
                                "authorizedEventIds", Set.of(event1.getId())
                        )))));

        // Mock the authorization-service batch-check endpoint for user2 (only has access to event2)
        stubFor(post(urlEqualTo("/api/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user2")))
                .withRequestBody(matchingJsonPath("$.eventIds[?(@ == " + event2.getId() + ")]"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user2",
                                "authorizedEventIds", Set.of(event2.getId())
                        )))));

        // Search as user1 - should only see event1
        Flux<Event> user1Response = webTestClient.get()
                .uri("/api/search?q=Event")
                .header("X-User-Id", "user1")
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(user1Response)
                .expectNextMatches(event -> event.getTitle().equals("Public Event"))
                .verifyComplete();

        // Search as user2 - should only see event2
        Flux<Event> user2Response = webTestClient.get()
                .uri("/api/search?q=Event")
                .header("X-User-Id", "user2")
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(user2Response)
                .expectNextMatches(event -> event.getTitle().equals("Private Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return empty results for user with no permissions")
    void shouldReturnEmptyResultsForUserWithNoPermissions() throws Exception {
        // Create an event
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Test content")).block();
        elasticsearchRepository.save(event).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Mock the authorization-service batch-check endpoint to return empty authorized IDs for user3
        stubFor(post(urlEqualTo("/api/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user3")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user3",
                                "authorizedEventIds", Set.of()
                        )))));

        // Search as user3 who has no permissions
        Flux<Event> user3Response = webTestClient.get()
                .uri("/api/search?q=Test")
                .header("X-User-Id", "user3")
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(user3Response)
                .verifyComplete();
    }

}
