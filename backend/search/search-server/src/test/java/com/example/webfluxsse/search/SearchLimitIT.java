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
import java.util.*;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Search Limit Integration Tests")
class SearchLimitIT {

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
    registry.add(
        "spring.r2dbc.url",
        () -> postgres.getJdbcUrl().replace("jdbc:postgresql://", "r2dbc:postgresql://"));
    registry.add("spring.r2dbc.username", postgres::getUsername);
    registry.add("spring.r2dbc.password", postgres::getPassword);
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
    elasticsearchRepository.deleteAll().block();
    eventRepository.deleteAll().block();
    MOCK_SERVER.resetAll();
  }

  @AfterEach
  void tearDown() {
    MOCK_SERVER.resetAll();
  }

  @Test
  @DisplayName("Should respect custom limit when provided (NDJSON)")
  void shouldRespectCustomLimitWhenProvidedNdjson() throws Exception {
    // Create 50 events
    int totalEvents = 50;
    List<Event> events = createAndIndexEvents(totalEvents, "Limited");
    String userId = "user1";

    // User has access to all events
    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);

    Thread.sleep(2000);

    // Search with limit of 10
    Flux<Event> responseBody =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Limited", "userId", userId, "limit", 10))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody).expectNextCount(10).verifyComplete();
  }

  @Test
  @DisplayName("Should use default limit of 200 when not provided (NDJSON)")
  void shouldUseDefaultLimitWhenNotProvidedNdjson() throws Exception {
    // Create 250 events
    int totalEvents = 250;
    List<Event> events = createAndIndexEvents(totalEvents, "Default");
    String userId = "user1";

    // User has access to all events
    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);

    Thread.sleep(2000);

    // Search without limit (should default to 200)
    Flux<Event> responseBody =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Default", "userId", userId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody).expectNextCount(200).verifyComplete();
  }

  @Test
  @DisplayName("Should respect custom limit with SSE endpoint (POST)")
  void shouldRespectCustomLimitWithSsePost() throws Exception {
    // Create 30 events
    int totalEvents = 30;
    List<Event> events = createAndIndexEvents(totalEvents, "SSE");
    String userId = "user1";

    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);

    Thread.sleep(2000);

    // Search with limit of 5
    Flux<Event> responseBody =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(Map.of("query", "SSE", "userId", userId, "limit", 5))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody).expectNextCount(5).verifyComplete();
  }

  @Test
  @DisplayName("Should respect custom limit with SSE endpoint (GET)")
  void shouldRespectCustomLimitWithSseGet() throws Exception {
    // Create 25 events
    int totalEvents = 25;
    List<Event> events = createAndIndexEvents(totalEvents, "GET");
    String userId = "user1";

    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);

    Thread.sleep(2000);

    // Search with limit of 7
    Flux<Event> responseBody =
        webTestClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/api/v1/search/sse")
                        .queryParam("query", "GET")
                        .queryParam("userId", userId)
                        .queryParam("limit", 7)
                        .build())
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody).expectNextCount(7).verifyComplete();
  }

  @Test
  @DisplayName("Should only count authorized events toward limit")
  void shouldOnlyCountAuthorizedEventsTowardLimit() throws Exception {
    // Create 30 events
    int totalEvents = 30;
    List<Event> events = createAndIndexEvents(totalEvents, "Filtered");
    String userId = "user1";

    // User has access to only 15 events (even numbered)
    Set<Long> authorizedEventIds = new HashSet<>();
    for (int i = 0; i < events.size(); i++) {
      if (i % 2 == 0) {
        authorizedEventIds.add(events.get(i).id());
      }
    }
    mockBatchPermissions(userId, authorizedEventIds);

    Thread.sleep(2000);

    // Search with limit of 20 - should get only 15 (all authorized events)
    Flux<Event> responseBody =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Filtered", "userId", userId, "limit", 20))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody).expectNextCount(15).verifyComplete();
  }

  @Test
  @DisplayName("Should handle limit when user has more authorized events than limit")
  void shouldHandleLimitWithMoreAuthorizedEvents() throws Exception {
    // Create 100 events
    int totalEvents = 100;
    List<Event> events = createAndIndexEvents(totalEvents, "Many");
    String userId = "user1";

    // User has access to 60 events (even numbered)
    Set<Long> authorizedEventIds = new HashSet<>();
    for (int i = 0; i < events.size(); i++) {
      if (i % 2 == 0) {
        authorizedEventIds.add(events.get(i).id());
      }
    }
    mockBatchPermissions(userId, authorizedEventIds);

    Thread.sleep(2000);

    // Search with limit of 25 - should get exactly 25 authorized events
    Flux<Event> responseBody =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Many", "userId", userId, "limit", 25))
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody).expectNextCount(25).verifyComplete();
  }

  @Test
  @DisplayName("Should handle limit for query-less search")
  void shouldHandleLimitForQuerylessSearch() throws Exception {
    // Create 40 events
    int totalEvents = 40;
    List<Event> events = createAndIndexEvents(totalEvents, "All");
    String userId = "user1";

    // User has access to all events
    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());

    // Mock the GET endpoint for query-less search
    stubFor(
        WireMock.get(urlEqualTo("/api/v1/permissions/user/" + userId + "/events"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(objectMapper.writeValueAsString(new ArrayList<>(allEventIds)))));

    Thread.sleep(2000);

    // Search without query but with limit
    Flux<Event> responseBody =
        webTestClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/api/v1/search/sse")
                        .queryParam("userId", userId)
                        .queryParam("limit", 15)
                        .build())
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Event.class)
            .getResponseBody();

    StepVerifier.create(responseBody).expectNextCount(15).verifyComplete();
  }

  // Helper methods

  private List<Event> createAndIndexEvents(int count, String titlePrefix) {
    List<Event> events = new ArrayList<>();
    List<EventEntity> entities = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      Event event = new Event(LocalDateTime.now(), titlePrefix + " Event " + i, "Content " + i);
      EventEntity savedEntity = eventRepository.save(EventMapper.toEntity(event)).block();
      entities.add(savedEntity);
      events.add(EventMapper.toDto(savedEntity));
    }

    elasticsearchRepository.saveAll(entities).collectList().block();
    return events;
  }

  private void mockBatchPermissions(String userId, Set<Long> authorizedEventIds) throws Exception {
    // Mock batch permission checks for different batch sizes
    // We need to handle multiple batches since SearchService buffers in batches of 20
    List<Long> sortedIds = new ArrayList<>(authorizedEventIds);
    sortedIds.sort(Long::compareTo);

    for (int i = 0; i < sortedIds.size(); i += 20) {
      List<Long> batch = sortedIds.subList(i, Math.min(i + 20, sortedIds.size()));
      Set<Long> batchSet = new HashSet<>(batch);

      stubFor(
          post(urlEqualTo("/api/v1/permissions/batch-check"))
              .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
              .withRequestBody(
                  matchingJsonPath("$.eventIds[0]", equalTo(String.valueOf(batch.get(0)))))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "userId", userId,
                                  "authorizedEventIds", batchSet)))));
    }

    // Also add a catch-all stub for any batch that doesn't match specific ones
    stubFor(
        post(urlEqualTo("/api/v1/permissions/batch-check"))
            .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "userId", userId,
                                "authorizedEventIds", authorizedEventIds)))));
  }
}
