package com.example.webfluxsse.search;

import com.example.webfluxsse.common.model.Event;
import com.example.webfluxsse.search.repository.r2dbc.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Event Controller Integration Tests with PostgreSQL and Elasticsearch Testcontainers")
class EventControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("init-test-db.sql");

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.8.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withStartupTimeout(Duration.ofMinutes(2));

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
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void setUp() {
        // Clean up existing data (table is created by container init script)
        eventRepository.deleteAll().block();
    }

    @Test
    @DisplayName("REST API should return all events from database in descending order by timestamp when multiple events exist")
    void shouldReturnAllEventsViaRestApi() {
        Event event1 = new Event(LocalDateTime.now().minusMinutes(10), "Test Event 1", "Description 1");
        Event event2 = new Event(LocalDateTime.now().minusMinutes(5), "Test Event 2", "Description 2");
        
        eventRepository.save(event1).block();
        eventRepository.save(event2).block();

        webTestClient.get()
                .uri("/api/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBodyList(Event.class)
                .hasSize(2)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).getTitle().equals("Test Event 2"); // Latest first
                    assert events.get(1).getTitle().equals("Test Event 1");
                });
    }

    @Test
    @DisplayName("REST API should return empty list with 200 OK status when no events exist in database")
    void shouldReturnEmptyListWhenNoEvents() {
        webTestClient.get()
                .uri("/api/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBodyList(Event.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("SSE endpoint should stream events with proper content-type and JSON payload when events exist")
    void shouldStreamEventsViaSse() {
        Event testEvent = new Event(LocalDateTime.now(), "SSE Test Event", "SSE Description");
        eventRepository.save(testEvent).block();

        Flux<String> eventStream = webTestClient.get()
                .uri("/api/events/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .take(1)
                .timeout(Duration.ofSeconds(5));

        StepVerifier.create(eventStream)
                .expectNextMatches(eventData -> 
                    eventData.contains("SSE Test Event") && 
                    eventData.contains("SSE Description"))
                .verifyComplete();
    }

    @Test
    @DisplayName("SSE endpoint should continuously stream events every 2 seconds with real-time updates")
    void shouldContinuouslyStreamEvents() {
        Event event1 = new Event(LocalDateTime.now().minusMinutes(1), "Stream Event 1", "Stream Description 1");
        Event event2 = new Event(LocalDateTime.now(), "Stream Event 2", "Stream Description 2");
        
        eventRepository.save(event1).block();
        eventRepository.save(event2).block();

        Flux<String> eventStream = webTestClient.get()
                .uri("/api/events/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .take(2)
                .timeout(Duration.ofSeconds(10));

        StepVerifier.create(eventStream)
                .expectNextMatches(eventData -> eventData.contains("Stream Event"))
                .expectNextMatches(eventData -> eventData.contains("Stream Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("SSE endpoint should detect and stream new events added to database during active connection")
    void shouldHandleEventUpdates() {
        Event initialEvent = new Event(LocalDateTime.now(), "Initial Event", "Initial Description");
        eventRepository.save(initialEvent).block();

        Flux<String> eventStream = webTestClient.get()
                .uri("/api/events/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .take(2)
                .timeout(Duration.ofSeconds(10));

        eventRepository.save(new Event(LocalDateTime.now(), "New Event", "New Description"))
                .delaySubscription(Duration.ofSeconds(1))
                .subscribe();

        StepVerifier.create(eventStream)
                .expectNextMatches(eventData -> eventData.contains("Initial Event"))
                .expectNextMatches(eventData -> eventData.contains("New Event"))
                .verifyComplete();
    }

    @Test
    @DisplayName("REST API should return events sorted by timestamp DESC with newest events first regardless of insertion order")
    void shouldReturnEventsInDescendingOrderByTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        Event oldEvent = new Event(now.minusHours(2), "Old Event", "Old Description");
        Event newEvent = new Event(now.minusMinutes(30), "New Event", "New Description");
        Event recentEvent = new Event(now, "Recent Event", "Recent Description");

        eventRepository.save(oldEvent).block();
        eventRepository.save(newEvent).block();
        eventRepository.save(recentEvent).block();

        webTestClient.get()
                .uri("/api/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(3)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).getTitle().equals("Recent Event");
                    assert events.get(1).getTitle().equals("New Event");
                    assert events.get(2).getTitle().equals("Old Event");
                });
    }

    @Test
    @DisplayName("REST API should return complete Event objects with all required fields (id, timestamp, title, description)")
    void shouldValidateEventStructure() {
        Event testEvent = new Event(LocalDateTime.now(), "Validation Event", "Validation Description");
        eventRepository.save(testEvent).block();

        webTestClient.get()
                .uri("/api/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(1)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    Event event = events.get(0);
                    assert event.getId() != null;
                    assert event.getTimestamp() != null;
                    assert event.getTitle().equals("Validation Event");
                    assert event.getDescription().equals("Validation Description");
                });
    }

    @Test
    @DisplayName("POST API should create new event with 201 status and return complete event object with generated ID and timestamp")
    void shouldCreateNewEventViaPOST() {
        String requestBody = """
            {
                "title": "Posted Event",
                "description": "Event created via POST API"
            }
            """;

        webTestClient.post()
                .uri("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(Event.class)
                .consumeWith(result -> {
                    Event createdEvent = result.getResponseBody();
                    assert createdEvent != null;
                    assert createdEvent.getId() != null;
                    assert createdEvent.getTimestamp() != null;
                    assert createdEvent.getTitle().equals("Posted Event");
                    assert createdEvent.getDescription().equals("Event created via POST API");
                });
    }

    @Test
    @DisplayName("POST API should persist event to database and make it available via GET API immediately after creation")
    void shouldPersistEventAndMakeItAvailableImmediately() {
        String requestBody = """
            {
                "title": "Persistence Test Event",
                "description": "Testing immediate persistence"
            }
            """;

        // Create event via POST
        webTestClient.post()
                .uri("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated();

        // Verify it's immediately available via GET
        webTestClient.get()
                .uri("/api/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(1)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).getTitle().equals("Persistence Test Event");
                    assert events.get(0).getDescription().equals("Testing immediate persistence");
                });
    }

    @Test
    @DisplayName("POST API should create events that become available via SSE streaming after creation")
    void shouldMakePostedEventsAvailableInSSEStream() {
        String requestBody = """
            {
                "title": "Stream Test Event",
                "description": "Event to test SSE availability"
            }
            """;

        // Create event via POST
        webTestClient.post()
                .uri("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated();

        // Verify the SSE endpoint is available and returns the expected content type
        // We won't consume the stream as it's infinite, but verify it starts correctly
        Flux<String> eventStream = webTestClient.get()
                .uri("/api/events/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .take(1)
                .timeout(Duration.ofSeconds(5));

        // Verify the first SSE event contains our posted event
        StepVerifier.create(eventStream)
                .expectNextMatches(eventData -> 
                    eventData.contains("Stream Test Event") && 
                    eventData.contains("Event to test SSE availability"))
                .verifyComplete();
    }
}