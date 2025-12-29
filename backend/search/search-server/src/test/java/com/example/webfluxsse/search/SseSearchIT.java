package com.example.webfluxsse.search;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.mapper.EventMapper;
import com.example.webfluxsse.search.model.EventEntity;
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
@DisplayName("SSE Search Integration Tests")
class SseSearchIT {

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
    @DisplayName("POST: Should search with SSE using request body")
    void shouldSearchWithSseUsingPostRequestBody() throws Exception {
        // Create events
        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(new Event(LocalDateTime.now(), "SSE Test Event", "Testing SSE streaming"))).block();
        Event event1 = EventMapper.toDto(entity1);

        // Index in Elasticsearch
        elasticsearchRepository.save(entity1).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Mock the authorization-service batch-check endpoint
        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user1")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user1",
                                "authorizedEventIds", Set.of(event1.id())
                        )))));

        // Search using POST with SSE
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("query", "SSE", "userId", "user1"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(responseBody)
                .expectNextMatches(event -> event.title().contains("SSE Test Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("POST: Should filter SSE results based on permissions")
    void shouldFilterSseResultsBasedOnPermissions() throws Exception {
        // Create two events
        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(new Event(LocalDateTime.now(), "User1 Event", "For user1"))).block();
        EventEntity entity2 = eventRepository.save(EventMapper.toEntity(new Event(LocalDateTime.now(), "User2 Event", "For user2"))).block();
        Event event1 = EventMapper.toDto(entity1);
        Event event2 = EventMapper.toDto(entity2);

        // Index in Elasticsearch
        elasticsearchRepository.save(entity1).block();
        elasticsearchRepository.save(entity2).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Mock the authorization-service for user1
        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user1")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user1",
                                "authorizedEventIds", Set.of(event1.id())
                        )))));

        // Search as user1 with SSE
        Flux<Event> user1Response = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("query", "Event", "userId", "user1"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(user1Response)
                .expectNextMatches(event -> event.title().equals("User1 Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("GET: Should search with SSE using query parameters")
    void shouldSearchWithSseUsingGetQueryParams() throws Exception {
        // Create event
        EventEntity entity = eventRepository.save(EventMapper.toEntity(new Event(LocalDateTime.now(), "GET SSE Event", "Testing GET endpoint"))).block();
        Event event = EventMapper.toDto(entity);

        // Index in Elasticsearch
        elasticsearchRepository.save(entity).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Mock the authorization-service
        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user1")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user1",
                                "authorizedEventIds", Set.of(event.id())
                        )))));

        // Search using GET with query parameters
        Flux<Event> responseBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/search/sse")
                        .queryParam("query", "GET")
                        .queryParam("userId", "user1")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(responseBody)
                .expectNextMatches(e -> e.title().contains("GET SSE Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("GET: Should handle empty query parameter")
    void shouldHandleEmptyQueryParameterInGetEndpoint() throws Exception {
        // Create event
        EventEntity entity = eventRepository.save(EventMapper.toEntity(new Event(LocalDateTime.now(), "Any Event", "Should be found"))).block();
        Event event = EventMapper.toDto(entity);

        // Index in Elasticsearch
        elasticsearchRepository.save(entity).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Mock the authorization-service GET endpoint for retrieving all event IDs for user
        // The endpoint returns a JSON array of event IDs (not an object)
        stubFor(WireMock.get(urlEqualTo("/api/v1/permissions/user/user1/events"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(java.util.List.of(event.id())))));
        // Search using GET without query parameter (should return all authorized events)
        Flux<Event> responseBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/search/sse")
                        .queryParam("userId", "user1")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(responseBody)
                .expectNextMatches(e -> e.title().equals("Any Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("POST: Should return empty stream for user with no permissions")
    void shouldReturnEmptyStreamForUserWithNoPermissions() throws Exception {
        // Create event
        EventEntity entity = eventRepository.save(EventMapper.toEntity(new Event(LocalDateTime.now(), "Restricted Event", "No access"))).block();
        elasticsearchRepository.save(entity).block();

        // Wait for indexing
        Thread.sleep(2000);

        // Mock empty permissions
        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("user3")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", "user3",
                                "authorizedEventIds", Set.of()
                        )))));

        // Search with SSE - should return empty stream
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("query", "Event", "userId", "user3"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(responseBody)
                .verifyComplete();
    }
}
