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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Search Default Limit (200) Integration Tests")
class SearchDefaultLimitIT {

  private static final WireMockServer MOCK_SERVER = new WireMockServer(0);
  private static final int DEFAULT_LIMIT = 200;

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

  // Parameterized test data
  static Stream<Arguments> searchEndpointProvider() {
    return Stream.of(
        Arguments.of("NDJSON POST", "ndjson-post", MediaType.APPLICATION_NDJSON),
        Arguments.of("SSE POST", "sse-post", MediaType.TEXT_EVENT_STREAM),
        Arguments.of("SSE GET", "sse-get", MediaType.TEXT_EVENT_STREAM));
  }

  @ParameterizedTest(name = "{0}: Should default to 200 results when limit not specified")
  @MethodSource("searchEndpointProvider")
  @DisplayName("Should default to 200 results when limit not specified")
  void shouldDefaultTo200ResultsWhenLimitNotSpecified(
      String endpointName, String endpointType, MediaType acceptType) throws Exception {

    // Create 250 events (more than default limit)
    int totalEvents = 250;
    String titlePrefix = "DefaultLimit" + endpointType.hashCode();  // Unique per test
    List<Event> events = createAndIndexEvents(totalEvents, titlePrefix);
    String userId = "user1";

    // User has access to all events
    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);

    // Wait for Elasticsearch to index
    Thread.sleep(2000);

    // Call endpoint without limit parameter
    // Use simple query term that matches the title prefix
    Flux<Event> responseBody = callSearchEndpoint(endpointType, acceptType, userId, "Event", null);

    // Verify we get exactly 200 results (the default limit)
    StepVerifier.create(responseBody).expectNextCount(DEFAULT_LIMIT).verifyComplete();
  }

  @ParameterizedTest(name = "{0}: Should use default 200 when limit is null")
  @MethodSource("searchEndpointProvider")
  @DisplayName("Should use default 200 when limit is null")
  void shouldUseDefault200WhenLimitIsNull(
      String endpointName, String endpointType, MediaType acceptType) throws Exception {

    // Create 300 events
    int totalEvents = 300;
    String titlePrefix = "NullLimit" + endpointType.hashCode();
    List<Event> events = createAndIndexEvents(totalEvents, titlePrefix);
    String userId = "user2";

    // User has access to all events
    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);

    Thread.sleep(2000);

    // Explicitly pass null as limit
    Flux<Event> responseBody = callSearchEndpoint(endpointType, acceptType, userId, "Event", null);

    StepVerifier.create(responseBody).expectNextCount(DEFAULT_LIMIT).verifyComplete();
  }

  @ParameterizedTest(name = "{0}: Should use default 200 when limit is 0")
  @MethodSource("searchEndpointProvider")
  @DisplayName("Should use default 200 when limit is 0 or negative")
  void shouldUseDefault200WhenLimitIsZeroOrNegative(
      String endpointName, String endpointType, MediaType acceptType) throws Exception {

    // Create 220 events
    int totalEvents = 220;
    String titlePrefix = "ZeroLimit" + endpointType.hashCode();
    List<Event> events = createAndIndexEvents(totalEvents, titlePrefix);
    String userId = "user3";

    // User has access to all events
    Set<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);

    Thread.sleep(2000);

    // Pass 0 as limit - should default to 200
    Flux<Event> responseBodyZero = callSearchEndpoint(endpointType, acceptType, userId, "Event", 0);
    StepVerifier.create(responseBodyZero).expectNextCount(DEFAULT_LIMIT).verifyComplete();

    // Clean and recreate for negative test
    elasticsearchRepository.deleteAll().block();
    eventRepository.deleteAll().block();
    MOCK_SERVER.resetAll();

    String titlePrefix2 = "NegLimit" + endpointType.hashCode();
    events = createAndIndexEvents(totalEvents, titlePrefix2);
    allEventIds = events.stream().map(Event::id).collect(Collectors.toSet());
    mockBatchPermissions(userId, allEventIds);
    Thread.sleep(2000);

    // Pass -1 as limit - should default to 200
    Flux<Event> responseBodyNegative = callSearchEndpoint(endpointType, acceptType, userId, "Event", -1);
    StepVerifier.create(responseBodyNegative).expectNextCount(DEFAULT_LIMIT).verifyComplete();
  }

  // Helper methods

  private Flux<Event> callSearchEndpoint(
      String endpointType, MediaType acceptType, String userId, String query, Integer limit)
      throws Exception {

    switch (endpointType) {
      case "ndjson-post":
        Map<String, Object> ndjsonBody = new HashMap<>();
        ndjsonBody.put("query", query);
        ndjsonBody.put("userId", userId);
        if (limit != null) {
          ndjsonBody.put("limit", limit);
        }
        return webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(ndjsonBody)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
            .returnResult(Event.class)
            .getResponseBody();

      case "sse-post":
        Map<String, Object> sseBody = new HashMap<>();
        sseBody.put("query", query);
        sseBody.put("userId", userId);
        if (limit != null) {
          sseBody.put("limit", limit);
        }
        return webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(sseBody)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(Event.class)
            .getResponseBody();

      case "sse-get":
        WebTestClient.RequestHeadersUriSpec<?> getRequest = webTestClient.get();
        WebTestClient.RequestHeadersSpec<?> uriSpec =
            getRequest.uri(
                uriBuilder -> {
                  var builder =
                      uriBuilder
                          .path("/api/v1/search/sse")
                          .queryParam("query", query)
                          .queryParam("userId", userId);
                  if (limit != null) {
                    builder.queryParam("limit", limit);
                  }
                  return builder.build();
                });
        return uriSpec
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(Event.class)
            .getResponseBody();

      default:
        throw new IllegalArgumentException("Unknown endpoint type: " + endpointType);
    }
  }

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
    // SearchService buffers in batches of 20
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
                              Map.of("userId", userId, "authorizedEventIds", batchSet)))));
    }

    // Catch-all stub for any batch that doesn't match specific ones
    stubFor(
        post(urlEqualTo("/api/v1/permissions/batch-check"))
            .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.writeValueAsString(
                            Map.of("userId", userId, "authorizedEventIds", authorizedEventIds)))));
  }
}
