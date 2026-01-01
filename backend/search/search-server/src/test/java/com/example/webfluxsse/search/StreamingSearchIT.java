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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Streaming Search Integration Tests")
class StreamingSearchIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine")
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

    private static WireMockServer wireMockServer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
                () -> postgres.getJdbcUrl().replace("jdbc:postgresql://", "r2dbc:postgresql://"));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getFirstMappedPort());

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
        elasticsearchRepository.deleteAll().block();
        eventRepository.deleteAll().block();
        wireMockServer.resetAll();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.resetAll();
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
        Set<Long> batch1Authorized = batch1Ids.stream()
                .filter(authorizedEventIds::contains)
                .collect(Collectors.toSet());

        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                .withRequestBody(matchingJsonPath("$.eventIds[0]", equalTo(String.valueOf(batch1Ids.get(0)))))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "authorizedEventIds", batch1Authorized
                        )))));

        // Mock for second batch (events 20-24, which is 5 events)
        if (allEventIds.size() > 20) {
            List<Long> batch2Ids = allEventIds.subList(20, allEventIds.size());
            Set<Long> batch2Authorized = batch2Ids.stream()
                    .filter(authorizedEventIds::contains)
                    .collect(Collectors.toSet());

            stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                    .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                    .withRequestBody(matchingJsonPath("$.eventIds[0]", equalTo(String.valueOf(batch2Ids.get(0)))))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(objectMapper.writeValueAsString(Map.of(
                                    "userId", userId,
                                    "authorizedEventIds", batch2Authorized
                            )))));
        }

        // Allow ES to refresh
        Thread.sleep(2000);

        // 5. Perform Search
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "Stream", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody();

        // 6. Verify using StepVerifier
        StepVerifier.create(responseBody)
                .expectNextCount(12) // We expect 12 events (odd numbers from 0-24)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search with spaces in query")
    void shouldSearchWithSpacesInQuery() throws Exception {
        // 1. Create events with multi-word titles
        String userId = "user1";
        List<EventEntity> entities = new ArrayList<>();

        Event event1 = new Event(LocalDateTime.now(), "Important System Alert", "Critical system failure detected");
        Event event2 = new Event(LocalDateTime.now(), "User Login Success", "User authenticated successfully");
        Event event3 = new Event(LocalDateTime.now(), "Database Connection", "Connection pool exhausted");

        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(event1)).block();
        EventEntity entity2 = eventRepository.save(EventMapper.toEntity(event2)).block();
        EventEntity entity3 = eventRepository.save(EventMapper.toEntity(event3)).block();

        entities.add(entity1);
        entities.add(entity2);
        entities.add(entity3);

        // 2. Index in Elasticsearch
        elasticsearchRepository.saveAll(entities).collectList().block();

        // 3. Mock authorization - user1 has access to all events
        Set<Long> allEventIds = entities.stream().map(EventEntity::getId).collect(Collectors.toSet());

        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "authorizedEventIds", allEventIds
                        )))));

        // Allow ES to refresh
        Thread.sleep(2000);

        // 4. Search with multi-word query (should find "Important System Alert")
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "System Alert", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody();

        // 5. Verify we get the matching event
        StepVerifier.create(responseBody)
                .expectNextMatches(event -> event.title().contains("System Alert"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search in descriptions")
    void shouldSearchInDescriptions() throws Exception {
        // 1. Create events with searchable descriptions
        String userId = "user1";
        List<EventEntity> entities = new ArrayList<>();

        Event event1 = new Event(LocalDateTime.now(), "Event A", "Authentication process completed successfully");
        Event event2 = new Event(LocalDateTime.now(), "Event B", "Database query optimization");
        Event event3 = new Event(LocalDateTime.now(), "Event C", "Network timeout during authentication");

        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(event1)).block();
        EventEntity entity2 = eventRepository.save(EventMapper.toEntity(event2)).block();
        EventEntity entity3 = eventRepository.save(EventMapper.toEntity(event3)).block();

        entities.add(entity1);
        entities.add(entity2);
        entities.add(entity3);

        // 2. Index in Elasticsearch
        elasticsearchRepository.saveAll(entities).collectList().block();

        // 3. Mock authorization - user1 has access to all events
        Set<Long> allEventIds = entities.stream().map(EventEntity::getId).collect(Collectors.toSet());

        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "authorizedEventIds", allEventIds
                        )))));

        // Allow ES to refresh
        Thread.sleep(2000);

        // 4. Search for "authentication" - should find events 1 and 3 (in descriptions)
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "authentication", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody();

        // 5. Verify we get events with "authentication" in description
        StepVerifier.create(responseBody.collectList())
                .expectNextMatches(events -> {
                    return events.size() == 2 &&
                           events.stream().allMatch(e -> e.description().toLowerCase().contains("authentication"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle special characters in search query")
    void shouldHandleSpecialCharactersInQuery() throws Exception {
        // 1. Create events with special characters
        String userId = "user1";
        List<EventEntity> entities = new ArrayList<>();

        Event event1 = new Event(LocalDateTime.now(), "API/v1 endpoint", "REST API endpoint for users");
        Event event2 = new Event(LocalDateTime.now(), "Error: 404", "Page not found error");
        Event event3 = new Event(LocalDateTime.now(), "User@Domain", "Email validation");

        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(event1)).block();
        EventEntity entity2 = eventRepository.save(EventMapper.toEntity(event2)).block();
        EventEntity entity3 = eventRepository.save(EventMapper.toEntity(event3)).block();

        entities.add(entity1);
        entities.add(entity2);
        entities.add(entity3);

        // 2. Index in Elasticsearch
        elasticsearchRepository.saveAll(entities).collectList().block();

        // 3. Mock authorization - user1 has access to all events
        Set<Long> allEventIds = entities.stream().map(EventEntity::getId).collect(Collectors.toSet());

        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "authorizedEventIds", allEventIds
                        )))));

        // Allow ES to refresh
        Thread.sleep(2000);

        // 4. Search for "API" - should find event with "API/v1 endpoint"
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "API", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody();

        // 5. Verify we get the API event
        StepVerifier.create(responseBody)
                .expectNextMatches(event -> event.title().contains("API") || event.description().contains("API"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search exact phrase with quotes")
    void shouldSearchExactPhraseWithQuotes() throws Exception {
        // 1. Create events with similar but different phrases
        String userId = "user1";
        List<EventEntity> entities = new ArrayList<>();

        Event event1 = new Event(LocalDateTime.now(), "Critical system failure detected", "Production database offline");
        Event event2 = new Event(LocalDateTime.now(), "System failure was critical yesterday", "Resolved now");
        Event event3 = new Event(LocalDateTime.now(), "Critical failure in system components", "Under investigation");
        Event event4 = new Event(LocalDateTime.now(), "Routine maintenance", "System is operational");

        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(event1)).block();
        EventEntity entity2 = eventRepository.save(EventMapper.toEntity(event2)).block();
        EventEntity entity3 = eventRepository.save(EventMapper.toEntity(event3)).block();
        EventEntity entity4 = eventRepository.save(EventMapper.toEntity(event4)).block();

        entities.add(entity1);
        entities.add(entity2);
        entities.add(entity3);
        entities.add(entity4);

        // 2. Index in Elasticsearch
        elasticsearchRepository.saveAll(entities).collectList().block();

        // 3. Mock authorization - user1 has access to all events
        Set<Long> allEventIds = entities.stream().map(EventEntity::getId).collect(Collectors.toSet());

        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "authorizedEventIds", allEventIds
                        )))));

        // Allow ES to refresh
        Thread.sleep(2000);

        // 4. Search with exact phrase "Critical system failure" - should only find event1
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "\"Critical system failure\"", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody();

        // 5. Verify we only get the exact match (event1)
        StepVerifier.create(responseBody.collectList())
                .expectNextMatches(events -> {
                    return events.size() == 1 &&
                           events.get(0).title().equals("Critical system failure detected");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should differentiate between exact phrase and regular search")
    void shouldDifferentiateBetweenExactPhraseAndRegularSearch() throws Exception {
        // 1. Create events
        String userId = "user1";
        List<EventEntity> entities = new ArrayList<>();

        Event event1 = new Event(LocalDateTime.now(), "User login successful", "Authentication completed");
        Event event2 = new Event(LocalDateTime.now(), "Login successful for user", "New session created");
        Event event3 = new Event(LocalDateTime.now(), "Successful login user account", "Access granted");

        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(event1)).block();
        EventEntity entity2 = eventRepository.save(EventMapper.toEntity(event2)).block();
        EventEntity entity3 = eventRepository.save(EventMapper.toEntity(event3)).block();

        entities.add(entity1);
        entities.add(entity2);
        entities.add(entity3);

        // 2. Index in Elasticsearch
        elasticsearchRepository.saveAll(entities).collectList().block();

        // 3. Mock authorization - user1 has access to all events
        Set<Long> allEventIds = entities.stream().map(EventEntity::getId).collect(Collectors.toSet());

        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "authorizedEventIds", allEventIds
                        )))));

        // Allow ES to refresh
        Thread.sleep(2000);

        // 4a. Regular search for "user login" - should find all 3 events
        Flux<Event> regularSearchBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "user login", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(regularSearchBody.collectList())
                .expectNextMatches(events -> events.size() == 3)
                .verifyComplete();

        // 4b. Exact phrase search for "User login successful" - should only find event1
        Flux<Event> exactSearchBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "\"User login successful\"", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Event.class)
                .getResponseBody();

        StepVerifier.create(exactSearchBody.collectList())
                .expectNextMatches(events -> {
                    return events.size() == 1 &&
                           events.get(0).title().equals("User login successful");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search exact phrase in descriptions")
    void shouldSearchExactPhraseInDescriptions() throws Exception {
        // 1. Create events with phrases in descriptions
        String userId = "user1";
        List<EventEntity> entities = new ArrayList<>();

        Event event1 = new Event(LocalDateTime.now(), "Event A", "Connection timeout occurred during database query");
        Event event2 = new Event(LocalDateTime.now(), "Event B", "Database query timeout during connection");
        Event event3 = new Event(LocalDateTime.now(), "Event C", "Query timeout connection occurred elsewhere");

        EventEntity entity1 = eventRepository.save(EventMapper.toEntity(event1)).block();
        EventEntity entity2 = eventRepository.save(EventMapper.toEntity(event2)).block();
        EventEntity entity3 = eventRepository.save(EventMapper.toEntity(event3)).block();

        entities.add(entity1);
        entities.add(entity2);
        entities.add(entity3);

        // 2. Index in Elasticsearch
        elasticsearchRepository.saveAll(entities).collectList().block();

        // 3. Mock authorization - user1 has access to all events
        Set<Long> allEventIds = entities.stream().map(EventEntity::getId).collect(Collectors.toSet());

        stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo(userId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "authorizedEventIds", allEventIds
                        )))));

        // Allow ES to refresh
        Thread.sleep(2000);

        // 4. Search with exact phrase "timeout occurred during" - should only find event1
        Flux<Event> responseBody = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("query", "\"timeout occurred during\"", "userId", userId))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody();

        // 5. Verify we only get event1
        StepVerifier.create(responseBody.collectList())
                .expectNextMatches(events -> {
                    return events.size() == 1 &&
                           events.get(0).description().equals("Connection timeout occurred during database query");
                })
                .verifyComplete();
    }
}
