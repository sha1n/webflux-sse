package com.example.virtualthreads.search;

import com.example.search.api.model.Event;
import com.example.virtualthreads.search.mapper.EventMapper;
import com.example.virtualthreads.search.model.EventEntity;
import com.example.virtualthreads.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.virtualthreads.search.repository.jpa.EventRepository;
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
 * Comprehensive integration tests for the Virtual Threads search service, covering various
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
@DisplayName("Virtual Threads Search Service: Comprehensive Integration Tests")
class SearchServiceVtIT {

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
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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

        List<EventEntity> savedEntities = new ArrayList<>();
        for (EventEntity entity : entities) {
            savedEntities.add(eventRepository.save(entity));
        }
        elasticsearchRepository.saveAll(savedEntities);

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
    void setUp() throws InterruptedException {
        elasticsearchRepository.deleteAll();
        eventRepository.deleteAll();
        wireMockServer.resetAll();
    }

    @Test
    void test_shouldReturnAllEventsWhenAllAreAuthorizedAndBelowLimit() throws Exception {
        List<EventEntity> createdEvents = createAndIndexEvents(100);
        Set<Long> allIds = createdEvents.stream().map(EventEntity::getId).collect(Collectors.toSet());
        setupPermissionsMock("user1", allIds);

        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search/ndjson")
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
                .uri("/api/rpc/v1/search/ndjson")
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
                .uri("/api/rpc/v1/search/ndjson")
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
                .uri("/api/rpc/v1/search/ndjson")
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
                .uri("/api/rpc/v1/search/ndjson")
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
                .uri("/api/rpc/v1/search/ndjson")
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
                .uri("/api/rpc/v1/search/ndjson")
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
                .uri("/api/rpc/v1/search/ndjson")
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
}
