package com.example.webfluxsse.search;

import com.example.search.api.model.Event;
import com.example.webfluxsse.search.mapper.EventMapper;
import com.example.webfluxsse.search.model.EventEntity;
import com.example.webfluxsse.search.repository.r2dbc.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest(properties = "spring.profiles.active=it")
@Testcontainers
@DisplayName("EventRepository LIMIT 200 Tests")
class EventRepositoryLimitIT {

  private static final int REPOSITORY_LIMIT = 200;

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
    // Disable authorization service for this test
    registry.add("authorization-service.base-url", () -> "http://localhost:9999");
  }

  @Autowired private EventRepository eventRepository;

  @BeforeEach
  void setUp() {
    eventRepository.deleteAll().block();
  }

  @Test
  @DisplayName("Should return at most 200 events when more exist in database")
  void shouldReturnAtMost200EventsWhenMoreExist() {
    // Create 300 events
    int totalEvents = 300;
    List<EventEntity> entities = new ArrayList<>();

    for (int i = 0; i < totalEvents; i++) {
      Event event =
          new Event(
              LocalDateTime.now().plusSeconds(i), "Event " + i, "Description " + i);
      EventEntity entity = EventMapper.toEntity(event);
      entities.add(entity);
    }

    // Save all events
    eventRepository.saveAll(entities).collectList().block();

    // Query all events - should return only 200
    StepVerifier.create(eventRepository.findAllOrderByTimestampDesc())
        .expectNextCount(REPOSITORY_LIMIT)
        .verifyComplete();
  }

  @Test
  @DisplayName("Should return all events when less than 200 exist")
  void shouldReturnAllEventsWhenLessThan200Exist() {
    // Create 50 events
    int totalEvents = 50;
    List<EventEntity> entities = new ArrayList<>();

    for (int i = 0; i < totalEvents; i++) {
      Event event =
          new Event(
              LocalDateTime.now().plusSeconds(i), "Event " + i, "Description " + i);
      EventEntity entity = EventMapper.toEntity(event);
      entities.add(entity);
    }

    // Save all events
    eventRepository.saveAll(entities).collectList().block();

    // Query all events - should return all 50
    StepVerifier.create(eventRepository.findAllOrderByTimestampDesc())
        .expectNextCount(totalEvents)
        .verifyComplete();
  }

  @Test
  @DisplayName("Should return exactly 200 events when exactly 200 exist")
  void shouldReturnExactly200EventsWhen200Exist() {
    // Create exactly 200 events
    int totalEvents = 200;
    List<EventEntity> entities = new ArrayList<>();

    for (int i = 0; i < totalEvents; i++) {
      Event event =
          new Event(
              LocalDateTime.now().plusSeconds(i), "Event " + i, "Description " + i);
      EventEntity entity = EventMapper.toEntity(event);
      entities.add(entity);
    }

    // Save all events
    eventRepository.saveAll(entities).collectList().block();

    // Query all events - should return exactly 200
    StepVerifier.create(eventRepository.findAllOrderByTimestampDesc())
        .expectNextCount(REPOSITORY_LIMIT)
        .verifyComplete();
  }

  @Test
  @DisplayName("Should return most recent 200 events ordered by timestamp DESC")
  void shouldReturnMostRecent200EventsOrderedByTimestampDesc() {
    // Create 250 events with specific timestamps
    int totalEvents = 250;
    List<EventEntity> entities = new ArrayList<>();
    LocalDateTime baseTime = LocalDateTime.of(2024, 1, 1, 0, 0);

    for (int i = 0; i < totalEvents; i++) {
      Event event =
          new Event(
              baseTime.plusSeconds(i), // Increasing timestamps
              "Event " + i,
              "Description " + i);
      EventEntity entity = EventMapper.toEntity(event);
      entities.add(entity);
    }

    // Save all events
    eventRepository.saveAll(entities).collectList().block();

    // Query all events - should return most recent 200 in DESC order
    StepVerifier.create(eventRepository.findAllOrderByTimestampDesc())
        .expectNextCount(REPOSITORY_LIMIT)
        .verifyComplete();

    // Verify the first event is the most recent (Event 249)
    StepVerifier.create(
            eventRepository
                .findAllOrderByTimestampDesc()
                .take(1)
                .map(entity -> entity.getTitle()))
        .expectNext("Event 249")
        .verifyComplete();

    // Verify the last event in the result is Event 50 (249 - 199 = 50)
    StepVerifier.create(
            eventRepository
                .findAllOrderByTimestampDesc()
                .elementAt(199) // 0-indexed, so 199 is the 200th element
                .map(entity -> entity.getTitle()))
        .expectNext("Event 50")
        .verifyComplete();
  }

  @Test
  @DisplayName(
      "Should return at most 200 events since timestamp when more exist after that time")
  void shouldReturnAtMost200EventsSinceTimestampWhenMoreExist() {
    // Create 300 events
    int totalEvents = 300;
    List<EventEntity> entities = new ArrayList<>();
    LocalDateTime baseTime = LocalDateTime.of(2024, 1, 1, 0, 0);

    for (int i = 0; i < totalEvents; i++) {
      Event event =
          new Event(baseTime.plusSeconds(i), "Event " + i, "Description " + i);
      EventEntity entity = EventMapper.toEntity(event);
      entities.add(entity);
    }

    // Save all events
    eventRepository.saveAll(entities).collectList().block();

    // Query events since a timestamp that has 250 events after it
    // Event 49 has timestamp baseTime + 49 seconds
    // Events 50-299 (250 events) are after it
    LocalDateTime since = baseTime.plusSeconds(49);

    StepVerifier.create(eventRepository.findByTimestampAfterOrderByTimestampDesc(since))
        .expectNextCount(REPOSITORY_LIMIT)
        .verifyComplete();
  }

  @Test
  @DisplayName("Should return events after 'since' timestamp in DESC order")
  void shouldReturnEventsAfterSinceTimestampInDescOrder() {
    // Create 50 events with known timestamps
    int totalEvents = 50;
    List<EventEntity> entities = new ArrayList<>();
    LocalDateTime baseTime = LocalDateTime.of(2024, 1, 1, 0, 0);

    for (int i = 0; i < totalEvents; i++) {
      Event event =
          new Event(baseTime.plusSeconds(i), "Event " + i, "Description " + i);
      EventEntity entity = EventMapper.toEntity(event);
      entities.add(entity);
    }

    // Save all events
    eventRepository.saveAll(entities).collectList().block();

    // Query events after Event 29 (30 events should be returned: Event 30-49)
    LocalDateTime since = baseTime.plusSeconds(29);

    StepVerifier.create(eventRepository.findByTimestampAfterOrderByTimestampDesc(since))
        .expectNextCount(20) // Events 30-49 = 20 events
        .verifyComplete();

    // Verify the first event is Event 49 (most recent)
    StepVerifier.create(
            eventRepository
                .findByTimestampAfterOrderByTimestampDesc(since)
                .take(1)
                .map(entity -> entity.getTitle()))
        .expectNext("Event 49")
        .verifyComplete();
  }
}
