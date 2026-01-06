package com.example.webfluxsse.search.client;

import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckRequest;
import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import com.example.webfluxsse.authorization.api.dto.EventIdsForUserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Component
public class AuthorizationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServiceClient.class);
    private final RestClient restClient;
    private final Duration timeout;

    public AuthorizationServiceClient(@Value("${authorization-service.base-url}") String baseUrl,
                                      @Value("${authorization-service.timeout:5s}") Duration timeout) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.timeout = timeout;
        log.info("AuthorizationServiceClient initialized with baseUrl: {}, timeout: {}", baseUrl, timeout);
    }

    public BatchPermissionCheckResponse checkBatchPermissions(List<Long> eventIds, String userId) {
        log.debug("Checking batch permissions for userId: {}, eventIds: {}", userId, eventIds);
        BatchPermissionCheckRequest request = new BatchPermissionCheckRequest(eventIds, userId);
        try {
            BatchPermissionCheckResponse response = restClient.post()
                    .uri("/api/v1/permissions/batch-check")
                    .body(request)
                    .retrieve()
                    .toEntity(BatchPermissionCheckResponse.class)
                    .getBody();
            log.debug("Batch permission check successful: userId={}, authorizedCount={}",
                    userId, response.authorizedEventIds().size());
            return response;
        } catch (Exception error) {
            log.warn("Failed to check batch permissions for userId={}: {}. Returning empty permissions.",
                    userId, error.getMessage());
            return new BatchPermissionCheckResponse(userId, java.util.Set.of());
        }
    }

    public List<Long> getEventIdsForUser(String userId) {
        log.debug("Getting event IDs for userId: {}", userId);
        try {
            EventIdsForUserResponse response = restClient.get()
                    .uri("/api/v1/permissions/user/{userId}/events", userId)
                    .retrieve()
                    .toEntity(EventIdsForUserResponse.class)
                    .getBody();
            log.debug("Retrieved event IDs for userId: {}", userId);
            return response.eventIds();
        } catch (Exception error) {
            log.warn("Failed to get event IDs for userId={}: {}. Returning empty list.",
                    userId, error.getMessage());
            return Collections.emptyList();
        }
    }
}
