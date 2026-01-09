package com.example.webfluxsse.search;

import com.example.search.api.model.Event;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive integration tests for the WebFlux search service, covering various
 * scenarios related to event creation, Elasticsearch indexing, authorization filtering,
 * and search limits. This aims to thoroughly test the service's behavior under
 * different data distributions and authorization responses.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=it",
                "authorization-service.http-version=HTTP_1_1"
        }
)
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("WebFlux Search Service: Comprehensive Integration Tests")
class SearchServiceWfIT {

    private static final int DEFAULT_SEARCH_LIMIT = 200;

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

    private static WireMockServer wireMockServer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
                () -> "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getFirstMappedPort());

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

    private List<EventEntity> createAndIndexEvents(int count) throws InterruptedException {
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        List<EventEntity> entities = IntStream.range(0, count)
                .mapToObj(i -> new Event(baseTime.plusSeconds(i), "Event " + i + " for testing", "Description " + i))
                .map(EventMapper::toEntity)
                .collect(Collectors.toList());

        List<EventEntity> savedEntities = eventRepository.saveAll(entities).collectList().block();
        elasticsearchRepository.saveAll(savedEntities).collectList().block();

        // Give Elasticsearch time to index
        Thread.sleep(2000);
        return savedEntities;
    }

    private void setupPermissionsMock(String userId, Set<Long> authorizedIds) throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(
                                Map.of("userId", userId, "authorizedEventIds", authorizedIds)))));
    }

    @BeforeEach
    void setUp() {
        elasticsearchRepository.deleteAll().block();
        eventRepository.deleteAll().block();
        wireMockServer.resetAll();
    }

    @Test
    void test_shouldReturnAllEventsWhenAllAreAuthorizedAndBelowLimit() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(100);
        Set<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toSet());
        setupPermissionsMock("user1", allIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(100);
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    void test_shouldReturnLimitWhenMoreThanLimitAreAuthorized() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(300);
        Set<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toSet());
        setupPermissionsMock("user1", allIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(DEFAULT_SEARCH_LIMIT);
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    void test_shouldReturnZeroEventsWhenNoneAreAuthorized() throws Exception {
        createAndIndexEvents(100);
        setupPermissionsMock("user1", Collections.emptySet());

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().isEmpty();
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    void test_shouldReturnCorrectCountWithSparseAuthorizedEventsBelowLimit() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(1000);
        Set<Long> authorizedIds = new HashSet<>();
        List<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toList());

        // Authorize 50 events, sparsely distributed (e.g., every 20th event)
        int numAuthorized = 50;
        int step = createdEvents.size() / numAuthorized;
        IntStream.range(0, createdEvents.size())
                .filter(i -> i % step == 0)
                .limit(numAuthorized)
                .forEach(i -> authorizedIds.add(allIds.get(i)));
        
        // Ensure exact number of authorized IDs for the mock
        while (authorizedIds.size() < numAuthorized && authorizedIds.size() < allIds.size()) {
            authorizedIds.add(allIds.get(authorizedIds.size()));
        }
        assertThat(authorizedIds).hasSize(numAuthorized);

        setupPermissionsMock("user1", authorizedIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(numAuthorized);
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    void test_shouldReturnLimitWithSparseAuthorizedEventsAboveLimit() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(1000);
        Set<Long> authorizedIds = new HashSet<>();
        List<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toList());

        // Authorize 300 events, sparsely distributed
        int numAuthorized = 300;
        int step = createdEvents.size() / numAuthorized;
        IntStream.range(0, createdEvents.size())
                .filter(i -> i % step == 0)
                .limit(numAuthorized)
                .forEach(i -> authorizedIds.add(allIds.get(i)));
        
        // Ensure exact number of authorized IDs for the mock
        while (authorizedIds.size() < numAuthorized && authorizedIds.size() < allIds.size()) {
            authorizedIds.add(allIds.get(authorizedIds.size()));
        }
        assertThat(authorizedIds).hasSize(numAuthorized);

        setupPermissionsMock("user1", authorizedIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(DEFAULT_SEARCH_LIMIT); // Should return up to limit
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    void test_shouldReturnDefaultLimitWhenLimitNotProvided() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(300);
        Set<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toSet());
        setupPermissionsMock("user1", allIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1")) // No limit in request
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(DEFAULT_SEARCH_LIMIT);
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    void test_shouldReturnLessThanLimitIfFewAuthorizedAndBelowLimit() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(100);
        Set<Long> authorizedIds = new HashSet<>();
        List<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toList());

        // Authorize 10 events
        IntStream.range(0, 10).forEach(i -> authorizedIds.add(allIds.get(i)));
        assertThat(authorizedIds).hasSize(10);

        setupPermissionsMock("user1", authorizedIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(10);
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    void test_shouldHandleLargeNumberOfEventsWithLimitedAuthorized() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(5000); // Very large number of events
        Set<Long> authorizedIds = new HashSet<>();
        List<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toList());

        // Authorize 150 events, sparsely distributed throughout the 5000 events
        int numAuthorized = 150;
        int step = createdEvents.size() / numAuthorized;
        IntStream.range(0, createdEvents.size())
                .filter(i -> i % step == 0)
                .limit(numAuthorized)
                .forEach(i -> authorizedIds.add(allIds.get(i)));

        while (authorizedIds.size() < numAuthorized && authorizedIds.size() < allIds.size()) {
            authorizedIds.add(allIds.get(authorizedIds.size()));
        }
        assertThat(authorizedIds).hasSize(numAuthorized);


        setupPermissionsMock("user1", authorizedIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(numAuthorized);
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    @Test
    @DisplayName("Should correctly filter to only authorized events when permission checks preserve order")
    void test_shouldReproduceLiveDiscrepancyWithVtResults() throws Exception {
        // 1. Create 200 events in Elasticsearch
        List<EventEntity> createdEvents = createAndIndexEvents(200);

        // 2. Define 16 specific authorized event IDs (e.g., event IDs from index 0 to 15)
        Set<Long> authorizedIds = IntStream.range(0, 16)
                .mapToObj(i -> createdEvents.get(i).getId())
                .collect(Collectors.toSet());
        assertThat(authorizedIds).hasSize(16); // Sanity check

        // 3. Mock the authorization service to ONLY return these 16 IDs as authorized
        setupPermissionsMock("user1", authorizedIds);

        // 4. Perform a search request to the WF service
        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", DEFAULT_SEARCH_LIMIT)) // Use default limit (200)
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        // 5. Assert correct behavior: WF should return only the 16 authorized events
        // Previously this test reproduced a bug where ~122 results were returned due to
        // flatMap with concurrency breaking ordering. Now fixed with flatMapSequential.
        assertThat(results)
                .as("WF service should correctly return only the 16 authorized events")
                .hasSize(16);

        // Verify that the authorization service was called multiple times for batched permission checks
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }
}
