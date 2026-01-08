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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify the WF search service correctly handles authorization
 * filtering and returns the expected number of events, even with sparse distributions
 * of authorized events. This mirrors ReproduceVtBugIT to allow direct comparison.
 *
 * Scenario:
 * - Create N events (e.g., 1000)
 * - User is authorized for M events (e.g., 122) which are sparsely scattered.
 * - Search with limit=200
 * - Expected: M authorized events should be returned.
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
@DisplayName("Reproduce WF Bug: Incomplete Results with Authorization Filtering (WF Counterpart)")
class ReproduceWfBugIT {

    private static final int TOTAL_EVENTS_TO_CREATE = 1000;
    private static final int EXPECTED_AUTHORIZED_EVENTS = 122; // Based on user's live observation
    private static final int SEARCH_LIMIT = 200;

    @Container
    static org.testcontainers.containers.JdbcDatabaseContainer postgres = new PostgreSQLContainer("postgres:15-alpine")
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
    private EventRepository eventRepository; // Using R2DBC repository for WF

    @Autowired
    private EventElasticsearchRepository elasticsearchRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws InterruptedException {
        // Ensure clean state for each test
        elasticsearchRepository.deleteAll().block(); // Reactive call, use block in test setup
        eventRepository.deleteAll().block(); // Reactive call, use block in test setup
        wireMockServer.resetAll();

        // 1. Create TOTAL_EVENTS_TO_CREATE events
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        List<EventEntity> allCreatedEntities = new ArrayList<>();

        for (int i = 1; i <= TOTAL_EVENTS_TO_CREATE; i++) {
            Event event = new Event(
                    baseTime.plusSeconds(i),
                    "Event " + i + " for search query event", // Ensure query matches
                    "This is description " + i + " for query event"
            );
            EventEntity entity = EventMapper.toEntity(event);
            allCreatedEntities.add(entity);
        }

        // Save to JPA and then Elasticsearch (reactive way)
        eventRepository.saveAll(allCreatedEntities)
                .collectList()
                .flatMap(savedEntities -> elasticsearchRepository.saveAll(savedEntities).collectList())
                .block(); // Block to ensure setup is complete before test runs

        // Wait for Elasticsearch to index all documents
        Thread.sleep(3000);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.resetAll();
    }

    @Test
    void shouldReturnExpectedNumberOfEventsAfterAuthorizationFiltering() throws Exception {
        // 3. Determine which event IDs to authorize (EXPECTED_AUTHORIZED_EVENTS sparsely scattered across the range)
        List<Long> allIds = eventRepository.findAll()
                .map(EventEntity::getId)
                .collectList()
                .block();
        Set<Long> authorizedIds = new HashSet<>();

        // Select EXPECTED_AUTHORIZED_EVENTS ids, distributed to challenge pagination
        // If 1000 total events and 122 expected, choose roughly every 8th event (1000/122 ~ 8.19)
        int step = TOTAL_EVENTS_TO_CREATE / EXPECTED_AUTHORIZED_EVENTS;
        IntStream.range(0, TOTAL_EVENTS_TO_CREATE)
                .filter(i -> i % step == 0)
                .limit(EXPECTED_AUTHORIZED_EVENTS)
                .forEach(i -> authorizedIds.add(allIds.get(i)));

        // Ensure we actually authorized EXPECTED_AUTHORIZED_EVENTS
        assertThat(authorizedIds).hasSize(EXPECTED_AUTHORIZED_EVENTS);

        // 4. Mock authorization service to return only these authorized IDs for "user1"
        setupPermissionsMock("user1", authorizedIds);

        // 5. Perform the search with limit=200
        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "event", "userId", "user1", "limit", SEARCH_LIMIT))
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        // 6. Assert that the number of returned events matches EXPECTED_AUTHORIZED_EVENTS
        assertThat(results).isNotNull();
        assertThat(results.size())
                .as("Should return exactly " + EXPECTED_AUTHORIZED_EVENTS + " authorized events up to limit " + SEARCH_LIMIT)
                .isEqualTo(EXPECTED_AUTHORIZED_EVENTS);

        // Verify that authorization service was called multiple times (implies pagination/batching attempts)
        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    private void setupPermissionsMock(String userId, Set<Long> authorizedIds) throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(objectMapper.writeValueAsString(
                                Map.of("userId", userId, "authorizedEventIds", authorizedIds)))));
    }
}
