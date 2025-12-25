package com.example.webfluxsse.authorization;

import com.example.webfluxsse.authorization.api.model.UserEventPermission;
import com.example.webfluxsse.authorization.controller.PermissionController;
import com.example.webfluxsse.authorization.model.UserEventPermissionEntity;
import com.example.webfluxsse.authorization.repository.UserEventPermissionRepository;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.profiles.active=it")
@AutoConfigureWebTestClient
@Testcontainers
@DisplayName("Permission Controller Integration Tests")
class PermissionControllerIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("init-test-db.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL configuration
        registry.add("spring.r2dbc.url", () -> {
            String jdbcUrl = postgres.getJdbcUrl();
            return jdbcUrl.replace("jdbc:postgresql://", "r2dbc:postgresql://");
        });
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserEventPermissionRepository permissionRepository;

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

    private Long createEvent(String title, String description) {
        LocalDateTime timestamp = LocalDateTime.now();

        // Insert the event
        databaseClient.sql("INSERT INTO events (timestamp, title, description) VALUES (:timestamp, :title, :description)")
                .bind("timestamp", timestamp)
                .bind("title", title)
                .bind("description", description)
                .fetch()
                .rowsUpdated()
                .block();

        // Get the last inserted ID
        return databaseClient.sql("SELECT id FROM events WHERE timestamp = :timestamp AND title = :title AND description = :description ORDER BY id DESC LIMIT 1")
                .bind("timestamp", timestamp)
                .bind("title", title)
                .bind("description", description)
                .fetch()
                .first()
                .map(row -> ((Number) row.get("id")).longValue())
                .block();
    }

    @Test
    @DisplayName("Should get all permissions")
    void shouldGetAllPermissions() {
        Long event1Id = createEvent("Test Event 1", "Description 1");
        Long event2Id = createEvent("Test Event 2", "Description 2");
        
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user1")).block();
        permissionRepository.save(new UserEventPermissionEntity(event2Id, "user2")).block();
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user2")).block();

        webTestClient.get()
                .uri("/api/v1/permissions")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3);
    }

    @Test
    @DisplayName("Should get permissions for specific user")
    void shouldGetPermissionsForUser() {
        Long event1Id = createEvent("Test Event 1", "Description 1");
        Long event2Id = createEvent("Test Event 2", "Description 2");
        
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user1")).block();
        permissionRepository.save(new UserEventPermissionEntity(event2Id, "user1")).block();
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user2")).block();

        webTestClient.get()
                .uri("/api/v1/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(2);
    }

    @Test
    @DisplayName("Should get permissions for specific event")
    void shouldGetPermissionsForEvent() {
        Long event1Id = createEvent("Test Event 1", "Description 1");
        Long event2Id = createEvent("Test Event 2", "Description 2");
        
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user1")).block();
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user2")).block();
        permissionRepository.save(new UserEventPermissionEntity(event2Id, "user3")).block();

        webTestClient.get()
                .uri("/api/v1/permissions/event/" + event1Id)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(2);
    }

    @Test
    @DisplayName("Should get users for specific event")
    void shouldGetUsersForEvent() {
        Long eventId = createEvent("Test Event", "Description");
        
        permissionRepository.save(new UserEventPermissionEntity(eventId, "user1")).block();
        permissionRepository.save(new UserEventPermissionEntity(eventId, "user2")).block();
        permissionRepository.save(new UserEventPermissionEntity(eventId, "user3")).block();

        webTestClient.get()
                .uri("/api/v1/permissions/event/" + eventId + "/users")
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
        Long event1Id = createEvent("Test Event 1", "Description 1");
        Long event2Id = createEvent("Test Event 2", "Description 2");
        Long event3Id = createEvent("Test Event 3", "Description 3");
        
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user1")).block();
        permissionRepository.save(new UserEventPermissionEntity(event2Id, "user1")).block();
        permissionRepository.save(new UserEventPermissionEntity(event3Id, "user2")).block();

        webTestClient.get()
                .uri("/api/v1/permissions/user/user1/events")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Long.class)
                .hasSize(2)
                .contains(event1Id, event2Id);
    }

    @Test
    @DisplayName("Should grant permission successfully")
    void shouldGrantPermission() {
        Long eventId = createEvent("Test Event", "Description");

        PermissionController.GrantPermissionRequest request = 
            new PermissionController.GrantPermissionRequest(eventId, "user1");

        webTestClient.post()
                .uri("/api/v1/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UserEventPermission.class)
                .value(permission -> {
                    assert permission.eventId().equals(eventId);
                    assert permission.userId().equals("user1");
                });

        // Verify permission was saved
        webTestClient.get()
                .uri("/api/v1/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(1);
    }

    @Test
    @DisplayName("Should revoke permission successfully")
    void shouldRevokePermission() {
        Long eventId = createEvent("Test Event", "Description");
        permissionRepository.save(new UserEventPermissionEntity(eventId, "user1")).block();

        // Verify permission exists
        webTestClient.get()
                .uri("/api/v1/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(1);

        // Revoke permission
        webTestClient.delete()
                .uri("/api/v1/permissions/event/" + eventId + "/user/user1")
                .exchange()
                .expectStatus().isNoContent();

        // Verify permission was removed
        webTestClient.get()
                .uri("/api/v1/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Should check permission correctly when permission exists")
    void shouldCheckPermissionWhenExists() {
        Long eventId = createEvent("Test Event", "Description");
        permissionRepository.save(new UserEventPermissionEntity(eventId, "user1")).block();

        webTestClient.get()
                .uri("/api/v1/permissions/check?eventId=" + eventId + "&userId=user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PermissionController.PermissionCheckResponse.class)
                .value(response -> {
                    assert response.eventId().equals(eventId);
                    assert response.userId().equals("user1");
                    assert response.hasPermission();
                });
    }

    @Test
    @DisplayName("Should check permission correctly when permission does not exist")
    void shouldCheckPermissionWhenNotExists() {
        Long eventId = createEvent("Test Event", "Description");

        webTestClient.get()
                .uri("/api/v1/permissions/check?eventId=" + eventId + "&userId=user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PermissionController.PermissionCheckResponse.class)
                .value(response -> {
                    assert response.eventId().equals(eventId);
                    assert response.userId().equals("user1");
                    assert !response.hasPermission();
                });
    }

    @Test
    @DisplayName("Should return empty list for user with no permissions")
    void shouldReturnEmptyListForUserWithNoPermissions() {
        webTestClient.get()
                .uri("/api/v1/permissions/user/nonexistent")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Should return empty list for event with no permissions")
    void shouldReturnEmptyListForEventWithNoPermissions() {
        Long eventId = createEvent("Test Event", "Description");

        webTestClient.get()
                .uri("/api/v1/permissions/event/" + eventId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("Should handle duplicate permission grant gracefully")
    void shouldHandleDuplicatePermissionGrant() {
        Long eventId = createEvent("Test Event", "Description");
        permissionRepository.save(new UserEventPermissionEntity(eventId, "user1")).block();

        PermissionController.GrantPermissionRequest request = 
            new PermissionController.GrantPermissionRequest(eventId, "user1");

        webTestClient.post()
                .uri("/api/v1/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().is5xxServerError(); // Should fail due to unique constraint
    }

    @Test
    @DisplayName("Should grant permissions for multiple events at once")
    void shouldGrantPermissionsForMultipleEvents() {
        Long event1Id = createEvent("Test Event 1", "Description 1");
        Long event2Id = createEvent("Test Event 2", "Description 2");
        Long event3Id = createEvent("Test Event 3", "Description 3");

        PermissionController.GrantMultiplePermissionsRequest request = 
            new PermissionController.GrantMultiplePermissionsRequest(
                java.util.List.of(event1Id, event2Id, event3Id), 
                "user1"
            );

        webTestClient.post()
                .uri("/api/v1/permissions/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3)
                .value(permissions -> {
                    // Verify all three events have permissions for user1
                    java.util.Set<Long> eventIds = permissions.stream()
                        .map(UserEventPermission::eventId)
                        .collect(java.util.stream.Collectors.toSet());
                    assert eventIds.contains(event1Id);
                    assert eventIds.contains(event2Id);
                    assert eventIds.contains(event3Id);
                    
                    // Verify all permissions are for user1
                    assert permissions.stream().allMatch(p -> "user1".equals(p.userId()));
                });

        // Verify permissions were saved by checking user's permissions
        webTestClient.get()
                .uri("/api/v1/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3);
    }

    @Test
    @DisplayName("Should handle partial failure when granting multiple permissions")
    void shouldHandlePartialFailureForMultiplePermissions() {
        Long event1Id = createEvent("Test Event 1", "Description 1");
        Long event2Id = createEvent("Test Event 2", "Description 2");
        Long event3Id = createEvent("Test Event 3", "Description 3");
        
        // Pre-create permission for event1 only
        permissionRepository.save(new UserEventPermissionEntity(event1Id, "user1")).block();

        PermissionController.GrantMultiplePermissionsRequest request = 
            new PermissionController.GrantMultiplePermissionsRequest(
                java.util.List.of(event1Id, event2Id, event3Id), 
                "user1"
            );

        // The current implementation continues processing and only grants permissions that don't exist
        webTestClient.post()
                .uri("/api/v1/permissions/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBodyList(UserEventPermission.class)
                .hasSize(2) // Should only create permissions for event2 and event3 (event1 already exists)
                .value(permissions -> {
                    java.util.Set<Long> eventIds = permissions.stream()
                        .map(UserEventPermission::eventId)
                        .collect(java.util.stream.Collectors.toSet());
                    assert eventIds.contains(event2Id);
                    assert eventIds.contains(event3Id);
                    assert !eventIds.contains(event1Id); // Should not include the duplicate
                    
                    // Verify all permissions are for user1
                    assert permissions.stream().allMatch(p -> "user1".equals(p.userId()));
                });

        // Verify total permissions count - should have all 3
        webTestClient.get()
                .uri("/api/v1/permissions/user/user1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserEventPermission.class)
                .hasSize(3); // All three permissions should exist (1 pre-existing + 2 new)
    }
}