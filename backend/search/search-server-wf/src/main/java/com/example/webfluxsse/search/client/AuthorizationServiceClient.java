package com.example.webfluxsse.search.client;

import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckRequest;
import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Component
public class AuthorizationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServiceClient.class);
    private final WebClient webClient;
    private final Duration timeout;

    public AuthorizationServiceClient(WebClient webClient,
                                     @Value("${authorization-service.timeout:5s}") Duration timeout) {
        this.webClient = webClient;
        this.timeout = timeout;
        log.info("AuthorizationServiceClient initialized with shared WebClient, timeout: {}", timeout);
    }

    public Mono<BatchPermissionCheckResponse> checkBatchPermissions(List<Long> eventIds, String userId) {
        log.debug("Checking batch permissions for userId: {}, eventIds: {}", userId, eventIds);

        BatchPermissionCheckRequest request = new BatchPermissionCheckRequest(eventIds, userId);

        return webClient.post()
                .uri("/api/v1/permissions/batch-check")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(BatchPermissionCheckResponse.class)
                .timeout(timeout)
                .doOnSuccess(response -> log.debug("Batch permission check successful: userId={}, authorizedCount={}",
                        userId, response.authorizedEventIds().size()))
                .onErrorResume(error -> {
                    log.warn("Failed to check batch permissions for userId={}: {}. Returning empty permissions.",
                            userId, error.getMessage());
                    return Mono.just(new BatchPermissionCheckResponse(userId, java.util.Set.of()));
                });
    }

    public Flux<Long> getEventIdsForUser(String userId) {
        log.debug("Getting event IDs for userId: {}", userId);

        return webClient.get()
                .uri("/api/v1/permissions/user/{userId}/events", userId)
                .retrieve()
                .bodyToFlux(Long.class)
                .timeout(timeout)
                .doOnComplete(() -> log.debug("Retrieved event IDs for userId: {}", userId))
                .onErrorResume(error -> {
                    log.warn("Failed to get event IDs for userId={}: {}. Returning empty list.",
                            userId, error.getMessage());
                    return Flux.empty();
                });
    }
}
