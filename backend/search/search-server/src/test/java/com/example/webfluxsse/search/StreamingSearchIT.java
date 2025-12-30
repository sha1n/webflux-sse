package com.example.webfluxsse.search;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

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
import java.util.*;
import java.util.stream.Collectors;
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
@DisplayName("Streaming Search Integration Tests")
class StreamingSearchIT {

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
  @DisplayName("Should stream search results using NDJSON")
  void shouldStreamSearchResultsUsingNdjson() throws Exception {
    // 1. Create and Index Events (25 events to test batching > 20)
    int totalEvents = 25;
    List<Event> events = new ArrayList<>();
    List<EventEntity> entities = new ArrayList<>();
    String userId = "user1";

    for (int i = 0; i < totalEvents; i++) {
      Event event = new Event(LocalDateTime.now(), "Stream Event " + i, "Content " + i);
      EventEntity savedEntity = eventRepository.save(EventMapper.toEntity(event)).block();
      entities.add(savedEntity);
      events.add(EventMapper.toDto(savedEntity));
    }

    // 2. Index in Elasticsearch
    elasticsearchRepository.saveAll(entities).collectList().block();

    // 3. Determine which events user1 should have access to (odd numbered events = 12 events)
    Set<Long> authorizedEventIds = new HashSet<>();
    for (int i = 0; i < totalEvents; i++) {
      if (i % 2 != 0) {
        authorizedEventIds.add(events.get(i).id());
      }
    }

    // 4. Mock the authorization-service batch-check endpoint
    // The SearchService buffers in batches of 20, so we need to mock responses for each batch
    List<Long> allEventIds = events.stream().map(Event::id).collect(Collectors.toList());

    // Mock for first batch (events 0-19, which is 20 events)
    List<Long> batch1Ids = allEventIds.subList(0, Math.min(20, allEventIds.size()));
    Set<Long> batch1Authorized =
        batch1Ids.stream().filter(authorizedEventIds::contains).collect(Collectors.toSet());

    stubFor(
        post(urlEqualTo("/api/v1/permissions/batch-check"))
            .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
            .withRequestBody(
                matchingJsonPath("$.eventIds[0]", equalTo(String.valueOf(batch1Ids.get(0)))))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "userId", userId,
                                "authorizedEventIds", batch1Authorized)))));

    // Mock for second batch (events 20-24, which is 5 events)
    if (allEventIds.size() > 20) {
      List<Long> batch2Ids = allEventIds.subList(20, allEventIds.size());
      Set<Long> batch2Authorized =
          batch2Ids.stream().filter(authorizedEventIds::contains).collect(Collectors.toSet());

      stubFor(
          post(urlEqualTo("/api/v1/permissions/batch-check"))
              .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
              .withRequestBody(
                  matchingJsonPath("$.eventIds[0]", equalTo(String.valueOf(batch2Ids.get(0)))))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          objectMapper.writeValueAsString(
                              Map.of(
                                  "userId", userId,
                                  "authorizedEventIds", batch2Authorized)))));
    }

    // Allow ES to refresh
    Thread.sleep(2000);

    // 5. Perform Search
    Flux<Event> responseBody =
        webTestClient
            .post()
            .uri("/api/rpc/v1/search")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_NDJSON)
            .bodyValue(Map.of("query", "Stream", "userId", userId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
            .returnResult(Event.class)
            .getResponseBody();

    // 6. Verify using StepVerifier
    StepVerifier.create(responseBody)
        .expectNextCount(12) // We expect 12 events (odd numbers from 0-24)
        .verifyComplete();
  }
}
