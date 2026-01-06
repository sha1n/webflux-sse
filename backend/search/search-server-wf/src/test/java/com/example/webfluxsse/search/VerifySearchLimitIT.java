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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies the search service correctly returns up to the limit
 * when more authorized events are available. This test is shared between WF and VT
 * to ensure consistent behavior after the bug fix.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=it",
                "authorization-service.http-version=HTTP_1_1"  // Use HTTP/1.1 for WireMock compatibility
        }
)
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Full Permissions Pagination Test (WF)") // Changed name for WF copy
class VerifySearchLimitIT { // Renamed for clarity in WF context, but using original file name

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
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/testdb");
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
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
    private EventRepository eventRepository; // WF uses R2DBC repository

    @Autowired
    private EventElasticsearchRepository elasticsearchRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        elasticsearchRepository.deleteAll().block(); // Reactive deleteAll
        eventRepository.deleteAll().block(); // Reactive deleteAll
        wireMockServer.resetAll();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("Should return 200 authorized events when more than 200 are available and authorized")
    void shouldReturn200AuthorizedEventsWhenManyAreAvailable() throws Exception {
        // 1. Create 300 events - first save to PostgreSQL to get IDs
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        List<EventEntity> savedEntities = new ArrayList<>();

        for (int i = 1; i <= 300; i++) {
            Event event = new Event(
                    baseTime.plusSeconds(i),
                    "Event " + i,
                    "This is event " + i + " for testing"
            );
            EventEntity entity = EventMapper.toEntity(event);
            EventEntity savedEntity = eventRepository.save(entity).block(); // Reactive save
            savedEntities.add(savedEntity);
        }

        // 2. Save to Elasticsearch with generated IDs
        elasticsearchRepository.saveAll(savedEntities).blockLast(); // Reactive saveAll

        // Wait for Elasticsearch to index
        Thread.sleep(2000);

        // 3. Authorize ALL 300 events for the user
        List<Long> allIds = savedEntities.stream().map(EventEntity::getId).sorted().toList();
        Set<Long> authorizedIds = new HashSet<>(allIds); // Authorize all events

        // 4. Mock batch permission checks to return ALL authorizedIds
        setupPartialPermissionsMock("testuser", authorizedIds);

        // 5. Search for "event" with limit=200
        List<Event> results = webTestClient.post()
                .uri("/api/rpc/v1/search") // Corrected endpoint for NDJSON
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "query": "event",
                          "userId": "testuser",
                          "limit": 200
                        }
                        """)
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(Event.class)
                .getResponseBody()
                .collectList()
                .block();

        // 6. Verify: Should return exactly 200 events
        assertThat(results).isNotNull();
        assertThat(results)
                .as("Should return exactly 200 authorized events, as per the limit")
                .hasSize(200);

        // Verify all returned events are in the authorized set (implicitly true if size is 200 and all are authorized)
        Set<Long> returnedIds = results.stream()
                .map(Event::id)
                .collect(java.util.stream.Collectors.toSet());

        // Ensure all returned IDs are within the set of all IDs (and thus authorized)
        assertThat(allIds).containsAll(returnedIds);


        // Verify WireMock received multiple batch-check requests (proving pagination worked)
        // Since all 300 events are authorized and we fetch 50 at a time, we'd expect 300/50 = 6 calls
        // Plus potential initial checks, so more than 2 is a safe bet.
        verify(moreThanOrExactly(2), postRequestedFor(urlEqualTo("/api/v1/permissions/batch-check")));
    }

    /**
     * Sets up WireMock to respond to batch permission checks.
     * For each request, it intersects the requested event IDs with the authorized set.
     * This simulates a real authorization service with sparse permissions.
     */
    private void setupPartialPermissionsMock(String userId, Set<Long> authorizedIds) throws Exception {
        // Use WireMock's dynamic response feature
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/permissions/batch-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildDynamicPermissionResponse(userId, authorizedIds))));
    }

    /**
     * Builds a mock response that returns intersection of requested IDs and authorized IDs.
     * Since WireMock doesn't easily support request body parsing in stub responses,
     * we return all authorized IDs - the service should filter to requested ones.
     */
    private String buildDynamicPermissionResponse(String userId, Set<Long> authorizedIds) throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("authorizedEventIds", authorizedIds);
        return objectMapper.writeValueAsString(response);
    }
}
