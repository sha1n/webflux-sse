package com.example.webfluxsse;

import com.example.webfluxsse.controller.PermissionController;
import com.example.webfluxsse.model.Event;
import com.example.webfluxsse.model.UserEventPermission;
import com.example.webfluxsse.repository.r2dbc.EventRepository;
import com.example.webfluxsse.repository.r2dbc.UserEventPermissionRepository;
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

import java.time.Duration;
import java.time.LocalDateTime;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Permission Controller Integration Tests")
class PermissionControllerIT {

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
    private UserEventPermissionRepository permissionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void setUp() {
        // Clean up in proper order due to foreign key constraints
        databaseClient.sql("DELETE FROM user_event_permissions").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM events").fetch().rowsUpdated().block();
        // Reset sequences for consistent IDs
        databaseClient.sql("ALTER SEQUENCE events_id_seq RESTART WITH 1").fetch().rowsUpdated().block();
        databaseClient.sql("ALTER SEQUENCE user_event_permissions_id_seq RESTART WITH 1").fetch().rowsUpdated().block();
    }

    @Test
    @DisplayName("Should get all permissions")
    void shouldGetAllPermissions() {
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 1", "Description 1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 2", "Description 2")).block();
        
        permissionRepository.save(new UserEventPermission(event1.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event2.getId(), "user2")).block();
        permissionRepository.save(new UserEventPermission(event1.getId(), "user2")).block();

        webTestClient.get()
                .uri("/api/permissions")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3);
    }

    @Test
    @DisplayName("Should get permissions for specific user")
    void shouldGetPermissionsForUser() {
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 1", "Description 1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 2", "Description 2")).block();
        
        permissionRepository.save(new UserEventPermission(event1.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event2.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event1.getId(), "user2")).block();

        webTestClient.get()
                .uri("/api/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(2);
    }

    @Test
    @DisplayName("Should get permissions for specific event")
    void shouldGetPermissionsForEvent() {
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 1", "Description 1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 2", "Description 2")).block();
        
        permissionRepository.save(new UserEventPermission(event1.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event1.getId(), "user2")).block();
        permissionRepository.save(new UserEventPermission(event2.getId(), "user3")).block();

        webTestClient.get()
                .uri("/api/permissions/event/" + event1.getId())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(2);
    }

    @Test
    @DisplayName("Should get users for specific event")
    void shouldGetUsersForEvent() {
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Description")).block();
        
        permissionRepository.save(new UserEventPermission(event.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event.getId(), "user2")).block();
        permissionRepository.save(new UserEventPermission(event.getId(), "user3")).block();

        webTestClient.get()
                .uri("/api/permissions/event/" + event.getId() + "/users")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(java.util.List.class)
                .value(users -> {
                    assert users.size() == 3;
                    assert users.contains("user1");
                    assert users.contains("user2");
                    assert users.contains("user3");
                });
    }

    @Test
    @DisplayName("Should get events for specific user")
    void shouldGetEventsForUser() {
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 1", "Description 1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 2", "Description 2")).block();
        Event event3 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 3", "Description 3")).block();
        
        permissionRepository.save(new UserEventPermission(event1.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event2.getId(), "user1")).block();
        permissionRepository.save(new UserEventPermission(event3.getId(), "user2")).block();

        webTestClient.get()
                .uri("/api/permissions/user/user1/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Long.class)
                .hasSize(2)
                .contains(event1.getId(), event2.getId());
    }

    @Test
    @DisplayName("Should grant permission successfully")
    void shouldGrantPermission() {
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Description")).block();

        PermissionController.GrantPermissionRequest request = 
            new PermissionController.GrantPermissionRequest(event.getId(), "user1");

        webTestClient.post()
                .uri("/api/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UserEventPermission.class)
                .value(permission -> {
                    assert permission.getEventId().equals(event.getId());
                    assert permission.getUserId().equals("user1");
                });

        // Verify permission was saved
        webTestClient.get()
                .uri("/api/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(1);
    }

    @Test
    @DisplayName("Should revoke permission successfully")
    void shouldRevokePermission() {
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Description")).block();
        permissionRepository.save(new UserEventPermission(event.getId(), "user1")).block();

        // Verify permission exists
        webTestClient.get()
                .uri("/api/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(1);

        // Revoke permission
        webTestClient.delete()
                .uri("/api/permissions/event/" + event.getId() + "/user/user1")
                .exchange()
                .expectStatus().isNoContent();

        // Verify permission was removed
        webTestClient.get()
                .uri("/api/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Should check permission correctly when permission exists")
    void shouldCheckPermissionWhenExists() {
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Description")).block();
        permissionRepository.save(new UserEventPermission(event.getId(), "user1")).block();

        webTestClient.get()
                .uri("/api/permissions/check?eventId=" + event.getId() + "&userId=user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PermissionController.PermissionCheckResponse.class)
                .value(response -> {
                    assert response.eventId().equals(event.getId());
                    assert response.userId().equals("user1");
                    assert response.hasPermission();
                });
    }

    @Test
    @DisplayName("Should check permission correctly when permission does not exist")
    void shouldCheckPermissionWhenNotExists() {
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Description")).block();

        webTestClient.get()
                .uri("/api/permissions/check?eventId=" + event.getId() + "&userId=user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PermissionController.PermissionCheckResponse.class)
                .value(response -> {
                    assert response.eventId().equals(event.getId());
                    assert response.userId().equals("user1");
                    assert !response.hasPermission();
                });
    }

    @Test
    @DisplayName("Should return empty list for user with no permissions")
    void shouldReturnEmptyListForUserWithNoPermissions() {
        webTestClient.get()
                .uri("/api/permissions/user/nonexistent")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Should return empty list for event with no permissions")
    void shouldReturnEmptyListForEventWithNoPermissions() {
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Description")).block();

        webTestClient.get()
                .uri("/api/permissions/event/" + event.getId())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Should handle duplicate permission grant gracefully")
    void shouldHandleDuplicatePermissionGrant() {
        Event event = eventRepository.save(new Event(LocalDateTime.now(), "Test Event", "Description")).block();
        permissionRepository.save(new UserEventPermission(event.getId(), "user1")).block();

        PermissionController.GrantPermissionRequest request = 
            new PermissionController.GrantPermissionRequest(event.getId(), "user1");

        webTestClient.post()
                .uri("/api/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().is5xxServerError(); // Should fail due to unique constraint
    }

    @Test
    @DisplayName("Should grant permissions for multiple events at once")
    void shouldGrantPermissionsForMultipleEvents() {
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 1", "Description 1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 2", "Description 2")).block();
        Event event3 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 3", "Description 3")).block();

        PermissionController.GrantMultiplePermissionsRequest request = 
            new PermissionController.GrantMultiplePermissionsRequest(
                java.util.List.of(event1.getId(), event2.getId(), event3.getId()), 
                "user1"
            );

        webTestClient.post()
                .uri("/api/permissions/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3)
                .value(permissions -> {
                    // Verify all three events have permissions for user1
                    java.util.Set<Long> eventIds = permissions.stream()
                        .map(UserEventPermission::getEventId)
                        .collect(java.util.stream.Collectors.toSet());
                    assert eventIds.contains(event1.getId());
                    assert eventIds.contains(event2.getId());
                    assert eventIds.contains(event3.getId());
                    
                    // Verify all permissions are for user1
                    assert permissions.stream().allMatch(p -> "user1".equals(p.getUserId()));
                });

        // Verify permissions were saved by checking user's permissions
        webTestClient.get()
                .uri("/api/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3);
    }

    @Test
    @DisplayName("Should handle partial failure when granting multiple permissions")
    void shouldHandlePartialFailureForMultiplePermissions() {
        Event event1 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 1", "Description 1")).block();
        Event event2 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 2", "Description 2")).block();
        Event event3 = eventRepository.save(new Event(LocalDateTime.now(), "Test Event 3", "Description 3")).block();
        
        // Pre-create permission for event1 only
        permissionRepository.save(new UserEventPermission(event1.getId(), "user1")).block();

        PermissionController.GrantMultiplePermissionsRequest request = 
            new PermissionController.GrantMultiplePermissionsRequest(
                java.util.List.of(event1.getId(), event2.getId(), event3.getId()), 
                "user1"
            );

        // The current implementation continues processing and only grants permissions that don't exist
        webTestClient.post()
                .uri("/api/permissions/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBodyList(UserEventPermission.class)
                .hasSize(2) // Should only create permissions for event2 and event3 (event1 already exists)
                .value(permissions -> {
                    java.util.Set<Long> eventIds = permissions.stream()
                        .map(UserEventPermission::getEventId)
                        .collect(java.util.stream.Collectors.toSet());
                    assert eventIds.contains(event2.getId());
                    assert eventIds.contains(event3.getId());
                    assert !eventIds.contains(event1.getId()); // Should not include the duplicate
                    
                    // Verify all permissions are for user1
                    assert permissions.stream().allMatch(p -> "user1".equals(p.getUserId()));
                });

        // Verify total permissions count - should have all 3
        webTestClient.get()
                .uri("/api/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3); // All three permissions should exist (1 pre-existing + 2 new)
    }
}