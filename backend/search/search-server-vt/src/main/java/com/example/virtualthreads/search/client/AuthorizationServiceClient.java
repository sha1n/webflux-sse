package com.example.virtualthreads.search.client;

import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckRequest;
import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import com.example.webfluxsse.authorization.api.dto.EventIdsForUserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

@Component
public class AuthorizationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServiceClient.class);
    private final RestClient restClient;
    private final Duration timeout;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AuthorizationServiceClient(RestClient restClient,
                                      @Value("${authorization-service.timeout:5s}") Duration timeout) {
        this.restClient = restClient;
        this.timeout = timeout;
        log.info("AuthorizationServiceClient initialized with timeout: {}", timeout);
    }

    public BatchPermissionCheckResponse checkBatchPermissions(List<Long> eventIds, String userId) {
        log.debug("Checking batch permissions for userId: {}, eventIds: {}", userId, eventIds);
        BatchPermissionCheckRequest request = new BatchPermissionCheckRequest(eventIds, userId);

        // Execute REST call with explicit timeout enforcement using CompletableFuture
        CompletableFuture<BatchPermissionCheckResponse> future = CompletableFuture.supplyAsync(() -> {
            try {
                BatchPermissionCheckResponse response = restClient.post()
                        .uri("/api/v1/permissions/batch-check")
                        .body(request)
                        .retrieve()
                        .toEntity(BatchPermissionCheckResponse.class)
                        .getBody();
                log.debug("Batch permission check successful: userId={}, authorizedCount={}",
                        userId, response != null ? response.authorizedEventIds().size() : 0);
                return response;
            } catch (Exception e) {
                log.warn("REST call failed for userId={}: {}", userId, e.getMessage());
                throw new CompletionException(e);
            }
        }, virtualThreadExecutor);

        try {
            BatchPermissionCheckResponse response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return response != null ? response : new BatchPermissionCheckResponse(userId, java.util.Set.of());
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Timeout after {}ms checking batch permissions for userId={}: {}. Returning empty permissions.",
                    timeout.toMillis(), userId, e.getMessage());
            return new BatchPermissionCheckResponse(userId, java.util.Set.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while checking batch permissions for userId={}: {}. Returning empty permissions.",
                    userId, e.getMessage());
            return new BatchPermissionCheckResponse(userId, java.util.Set.of());
        } catch (ExecutionException e) {
            log.warn("Failed to check batch permissions for userId={}: {}. Returning empty permissions.",
                    userId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return new BatchPermissionCheckResponse(userId, java.util.Set.of());
        }
    }

    public List<Long> getEventIdsForUser(String userId) {
        log.debug("Getting event IDs for userId: {}", userId);

        // Execute REST call with explicit timeout enforcement using CompletableFuture
        CompletableFuture<List<Long>> future = CompletableFuture.supplyAsync(() -> {
            try {
                EventIdsForUserResponse response = restClient.get()
                        .uri("/api/v1/permissions/user/{userId}/events", userId)
                        .retrieve()
                        .toEntity(EventIdsForUserResponse.class)
                        .getBody();
                log.debug("Retrieved {} event IDs for userId: {}",
                        response != null ? response.eventIds().size() : 0, userId);
                return response != null ? response.eventIds() : Collections.emptyList();
            } catch (Exception e) {
                log.warn("REST call failed for userId={}: {}", userId, e.getMessage());
                throw new CompletionException(e);
            }
        }, virtualThreadExecutor);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Timeout after {}ms getting event IDs for userId={}: {}. Returning empty list.",
                    timeout.toMillis(), userId, e.getMessage());
            return Collections.emptyList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while getting event IDs for userId={}: {}. Returning empty list.",
                    userId, e.getMessage());
            return Collections.emptyList();
        } catch (ExecutionException e) {
            log.warn("Failed to get event IDs for userId={}: {}. Returning empty list.",
                    userId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return Collections.emptyList();
        }
    }
}
