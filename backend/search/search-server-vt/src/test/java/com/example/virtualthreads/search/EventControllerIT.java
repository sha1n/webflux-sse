package com.example.virtualthreads.search;

import com.example.search.api.model.Event;
import com.example.virtualthreads.search.mapper.EventMapper;
import com.example.virtualthreads.search.repository.jpa.EventRepository;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Event Controller Integration Tests with PostgreSQL and Elasticsearch Testcontainers")
class EventControllerIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine")
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
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        
        // Elasticsearch configuration
        registry.add("spring.elasticsearch.uris", () -> 
            "http://" + elasticsearch.getHost() + ":" + elasticsearch.getFirstMappedPort());
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private EventRepository eventRepository;


    @BeforeEach
    void setUp() {
        // Clean up existing data (table is created by container init script)
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("REST API should return all events from database in descending order by timestamp when multiple events exist")
    void shouldReturnAllEventsViaRestApi() {
        Event event1 = new Event(null, LocalDateTime.now().minusMinutes(10), "Test Event 1", "Description 1");
        Event event2 = new Event(null, LocalDateTime.now().minusMinutes(5), "Test Event 2", "Description 2");

        eventRepository.save(EventMapper.toEntity(event1));
        eventRepository.save(EventMapper.toEntity(event2));

        webTestClient.get()
                .uri("/api/v1/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBodyList(Event.class)
                .hasSize(2)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).title().equals("Test Event 2"); // Latest first
                    assert events.get(1).title().equals("Test Event 1");
                });
    }

    @Test
    @DisplayName("REST API should return empty list with 200 OK status when no events exist in database")
    void shouldReturnEmptyListWhenNoEvents() {
        webTestClient.get()
                .uri("/api/v1/events")
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
        Event testEvent = new Event(null, LocalDateTime.now(), "SSE Test Event", "SSE Description");
        eventRepository.save(EventMapper.toEntity(testEvent));

        Flux<String> eventStream = webTestClient.get()
                .uri("/api/v1/events")
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
        Event event1 = new Event(null, LocalDateTime.now().minusMinutes(1), "Stream Event 1", "Stream Description 1");
        Event event2 = new Event(null, LocalDateTime.now(), "Stream Event 2", "Stream Description 2");
        
        eventRepository.save(EventMapper.toEntity(event1));
        eventRepository.save(EventMapper.toEntity(event2));

        Flux<String> eventStream = webTestClient.get()
                .uri("/api/v1/events")
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
        Event initialEvent = new Event(null, LocalDateTime.now(), "Initial Event", "Initial Description");
        eventRepository.save(EventMapper.toEntity(initialEvent));

        Flux<String> eventStream = webTestClient.get()
                .uri("/api/v1/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .take(2)
                .timeout(Duration.ofSeconds(10));

        // Save event asynchronously after 1 second delay (simulating delayed event arrival)
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000);
                eventRepository.save(EventMapper.toEntity(new Event(null, LocalDateTime.now(), "New Event", "New Description")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

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

        eventRepository.save(EventMapper.toEntity(oldEvent));
        eventRepository.save(EventMapper.toEntity(newEvent));
        eventRepository.save(EventMapper.toEntity(recentEvent));

        webTestClient.get()
                .uri("/api/v1/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(3)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.get(0).title().equals("Recent Event");
                    assert events.get(1).title().equals("New Event");
                    assert events.get(2).title().equals("Old Event");
                });
    }

    @Test
    @DisplayName("REST API should return complete Event objects with all required fields (id, timestamp, title, description)")
    void shouldValidateEventStructure() {
        Event testEvent = new Event(null, LocalDateTime.now(), "Validation Event", "Validation Description");
        eventRepository.save(EventMapper.toEntity(testEvent));

        webTestClient.get()
                .uri("/api/v1/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(1)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    Event event = events.get(0);
                    assert event.id() != null;
                    assert event.timestamp() != null;
                    assert event.title().equals("Validation Event");
                    assert event.description().equals("Validation Description");
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
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(Event.class)
                .consumeWith(result -> {
                    Event createdEvent = result.getResponseBody();
                    assert createdEvent != null;
                    assert createdEvent.id() != null;
                    assert createdEvent.timestamp() != null;
                    assert createdEvent.title().equals("Posted Event");
                    assert createdEvent.description().equals("Event created via POST API");
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
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated();

        // Verify it's immediately available via GET
        webTestClient.get()
                .uri("/api/v1/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(1)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.getFirst().title().equals("Persistence Test Event");
                    assert events.getFirst().description().equals("Testing immediate persistence");
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
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated();

        // Verify the SSE endpoint is available and returns the expected content type
        // We won't consume the stream as it's infinite, but verify it starts correctly
        Flux<String> eventStream = webTestClient.get()
                .uri("/api/v1/events")
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

    @Test
    @DisplayName("Bulk POST API should create multiple events with 201 status and return all created events with IDs")
    void shouldCreateMultipleEventsViaBulkPOST() {
        String requestBody = """
            {
                "events": [
                    {
                        "title": "Bulk Event 1",
                        "description": "First event in bulk creation"
                    },
                    {
                        "title": "Bulk Event 2",
                        "description": "Second event in bulk creation"
                    },
                    {
                        "title": "Bulk Event 3",
                        "description": "Third event in bulk creation"
                    }
                ]
            }
            """;

        webTestClient.post()
                .uri("/api/v1/events/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBodyList(Event.class)
                .hasSize(3)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    // Verify all events have IDs
                    assert events.stream().allMatch(e -> e.id() != null);
                    // Verify all expected titles are present (order may vary due to reactive processing)
                    assert events.stream().anyMatch(e -> e.title().equals("Bulk Event 1"));
                    assert events.stream().anyMatch(e -> e.title().equals("Bulk Event 2"));
                    assert events.stream().anyMatch(e -> e.title().equals("Bulk Event 3"));
                });
    }

    @Test
    @DisplayName("Bulk POST API should persist all events to database and make them available via GET API")
    void shouldPersistBulkEventsAndMakeThemAvailable() {
        String requestBody = """
            {
                "events": [
                    {
                        "title": "Bulk Persistence Test 1",
                        "description": "Testing bulk persistence 1"
                    },
                    {
                        "title": "Bulk Persistence Test 2",
                        "description": "Testing bulk persistence 2"
                    }
                ]
            }
            """;

        // Create events via bulk POST
        webTestClient.post()
                .uri("/api/v1/events/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBodyList(Event.class)
                .hasSize(2);

        // Verify both are available via GET
        webTestClient.get()
                .uri("/api/v1/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Event.class)
                .hasSize(2)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    assert events.stream().anyMatch(e -> e.title().equals("Bulk Persistence Test 1"));
                    assert events.stream().anyMatch(e -> e.title().equals("Bulk Persistence Test 2"));
                });
    }

    @Test
    @DisplayName("Bulk POST API should handle empty events list gracefully")
    void shouldHandleEmptyBulkRequest() {
        String requestBody = """
            {
                "events": []
            }
            """;

        webTestClient.post()
                .uri("/api/v1/events/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBodyList(Event.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Bulk POST API should handle large batches efficiently")
    void shouldHandleLargeBulkRequest() {
        // Build a request with 100 events
        StringBuilder jsonBuilder = new StringBuilder("{\"events\":[");
        for (int i = 1; i <= 100; i++) {
            if (i > 1) jsonBuilder.append(",");
            jsonBuilder.append(String.format(
                "{\"title\":\"Bulk Event %d\",\"description\":\"Description for event %d\"}",
                i, i
            ));
        }
        jsonBuilder.append("]}");

        webTestClient.post()
                .uri("/api/v1/events/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonBuilder.toString())
                .exchange()
                .expectStatus().isCreated()
                .expectBodyList(Event.class)
                .hasSize(100)
                .consumeWith(result -> {
                    var events = result.getResponseBody();
                    assert events != null;
                    // Verify first and last events
                    assert events.stream().anyMatch(e -> e.title().equals("Bulk Event 1"));
                    assert events.stream().anyMatch(e -> e.title().equals("Bulk Event 100"));
                    // Verify all have IDs
                    assert events.stream().allMatch(e -> e.id() != null);
                });
    }
}