package com.example.webfluxsse.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.example.webfluxsse.search.api.model.Event;
import com.example.webfluxsse.search.mapper.EventMapper;
import com.example.webfluxsse.search.model.EventEntity;
import com.example.webfluxsse.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.search.repository.r2dbc.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Search POC Integration Tests with PostgreSQL and Elasticsearch Testcontainers")
class SearchPocIntegrationIT {

  private static final WireMockServer MOCK_SERVER = new WireMockServer(0);

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:15-alpine")
          .withDatabaseName("testdb")
          .withUsername("testuser")
          .withPassword("testpass")
          .withInitScript("init-test-db.sql");

  @Container
  static ElasticsearchContainer elasticsearch =
      new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.8.0")
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withStartupTimeout(Duration.ofMinutes(2));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // PostgreSQL configuration
    registry.add(
        "spring.r2dbc.url",
        () -> {
          String jdbcUrl = postgres.getJdbcUrl();
          return jdbcUrl.replace("jdbc:postgresql://", "r2dbc:postgresql://");
        });
    registry.add("spring.r2dbc.username", postgres::getUsername);
    registry.add("spring.r2dbc.password", postgres::getPassword);

    // Elasticsearch configuration
    registry.add(
        "spring.elasticsearch.uris",
        () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getFirstMappedPort());

    MOCK_SERVER.start();
    WireMock.configureFor("localhost", MOCK_SERVER.port());
    registry.add("authorization-service.base-url", () -> "http://localhost:" + MOCK_SERVER.port());
  }

  @Autowired private WebTestClient webTestClient;

  @Autowired private EventRepository eventRepository;

  @Autowired private EventElasticsearchRepository elasticsearchRepository;

  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    // Clean up data
    elasticsearchRepository.deleteAll().block();
    eventRepository.deleteAll().block();
    MOCK_SERVER.resetAll();
  }

  @AfterEach
  void tearDown() {
    MOCK_SERVER.resetAll();
  }

  @Test
  @DisplayName("Should create event and make it searchable")
  void shouldCreateEventAndMakeItSearchable() throws Exception {
    // Create an event via API
    String requestBody =
        """
                        {
                            "title": "Test Event",
                            "description": "This is test content for searching"
                        }
                        """;

    Event createdEvent =
        webTestClient
            .post()
            .uri("/api/v1/events")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Event.class)
            .returnResult()
            .getResponseBody();

    Long eventId = createdEvent.id();

    // Wait for Elasticsearch indexing
    Thread.sleep(2000);

    // Mock the authorization-service batch-check endpoint to return this event as authorized for
    // user1
    stubFor(
        post(urlEqualTo("/api/v1/permissions/batch-check"))
            .withRequestBody(matchingJsonPath("$.userId", equalTo("user1")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.writeValueAsString(
                            Map.of("userId", "user1", "authorizedEventIds", Set.of(eventId))))));

    // Search for the event as user1
    Flux<Event> responseBody =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "test", "userId", "user1"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody)
        .expectNextMatches(event -> event.title().equals("Test Event"))
        .verifyComplete();
  }

  @Test
  @DisplayName("Should filter search results based on user permissions")
  void shouldFilterSearchResultsBasedOnUserPermissions() throws Exception {
    // Create two events
    EventEntity entity1 =
        eventRepository
            .save(
                EventMapper.toEntity(
                    new Event(LocalDateTime.now(), "Public Event", "Available to user1")))
            .block();
    EventEntity entity2 =
        eventRepository
            .save(
                EventMapper.toEntity(
                    new Event(LocalDateTime.now(), "Private Event", "Available to user2")))
            .block();
    Event event1 = EventMapper.toDto(entity1);
    Event event2 = EventMapper.toDto(entity2);

    // Index in Elasticsearch
    elasticsearchRepository.save(entity1).block();
    elasticsearchRepository.save(entity2).block();

    // Wait for indexing
    Thread.sleep(2000);

    // Mock the authorization-service batch-check endpoint for user1 (only has access to event1)
    stubFor(
        post(urlEqualTo("/api/v1/permissions/batch-check"))
            .withRequestBody(matchingJsonPath("$.userId", equalTo("user1")))
            .withRequestBody(matchingJsonPath("$.eventIds[?(@ == " + event1.id() + ")]"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "userId", "user1", "authorizedEventIds", Set.of(event1.id()))))));

    // Mock the authorization-service batch-check endpoint for user2 (only has access to event2)
    stubFor(
        post(urlEqualTo("/api/v1/permissions/batch-check"))
            .withRequestBody(matchingJsonPath("$.userId", equalTo("user2")))
            .withRequestBody(matchingJsonPath("$.eventIds[?(@ == " + event2.id() + ")]"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "userId", "user2", "authorizedEventIds", Set.of(event2.id()))))));

    // Search as user1 - should only see event1
    Flux<Event> user1Response =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Event", "userId", "user1"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(user1Response)
        .expectNextMatches(event -> event.title().equals("Public Event"))
        .verifyComplete();

    // Search as user2 - should only see event2
    Flux<Event> user2Response =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Event", "userId", "user2"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(user2Response)
        .expectNextMatches(event -> event.title().equals("Private Event"))
        .verifyComplete();
  }

  @Test
  @DisplayName("Should return empty results for user with no permissions")
  void shouldReturnEmptyResultsForUserWithNoPermissions() throws Exception {
    // Create an event
    EventEntity entity =
        eventRepository
            .save(
                EventMapper.toEntity(new Event(LocalDateTime.now(), "Test Event", "Test content")))
            .block();
    elasticsearchRepository.save(entity).block();

    // Wait for indexing
    Thread.sleep(2000);

    // Mock the authorization-service batch-check endpoint to return empty authorized IDs for user3
    stubFor(
        post(urlEqualTo("/api/v1/permissions/batch-check"))
            .withRequestBody(matchingJsonPath("$.userId", equalTo("user3")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.writeValueAsString(
                            Map.of("userId", "user3", "authorizedEventIds", Set.of())))));

    // Search as user3 who has no permissions
    Flux<Event> user3Response =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Test", "userId", "user3"))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(user3Response).verifyComplete();
  }
}
