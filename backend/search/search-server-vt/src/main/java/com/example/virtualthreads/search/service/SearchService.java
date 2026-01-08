package com.example.virtualthreads.search.service;

import com.example.search.api.model.Event;
import com.example.virtualthreads.search.client.AuthorizationServiceClient;
import com.example.virtualthreads.search.mapper.EventMapper;
import com.example.virtualthreads.search.model.EventEntity;
import com.example.virtualthreads.search.model.UserPermissionsResponse;
import com.example.virtualthreads.search.repository.elasticsearch.EventElasticsearchRepository;
import com.example.webfluxsse.authorization.api.dto.BatchPermissionCheckResponse;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "spring.elasticsearch.uris")
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final EventElasticsearchRepository elasticsearchRepository;
    private final AuthorizationServiceClient authorizationClient;

    public SearchService(EventElasticsearchRepository elasticsearchRepository,
                         AuthorizationServiceClient authorizationClient) {
        this.elasticsearchRepository = elasticsearchRepository;
        this.authorizationClient = authorizationClient;
        log.info("SearchService initialized with ElasticsearchOperations for proper pagination");
    }

    public Stream<Event> searchEventsForUser(String query, String userId, Integer limit) {
        int resultLimit = (limit != null && limit > 0) ? limit : 200;

        if (query == null || query.trim().isEmpty()) {
            return getEventsForUser(userId, resultLimit).stream();
        }

        String trimmedQuery = query.trim();
        boolean isExactPhrase = trimmedQuery.startsWith("\"") && trimmedQuery.endsWith("\"") && trimmedQuery.length() > 2;

        String searchQuery;
        if (isExactPhrase) {
            searchQuery = trimmedQuery.substring(1, trimmedQuery.length() - 1);
            log.debug("Exact phrase search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
        } else {
            searchQuery = trimmedQuery;
            log.debug("Full-text search for query='{}', userId='{}', limit={}", searchQuery, userId, resultLimit);
        }

        var stream = isExactPhrase
                ? elasticsearchRepository.searchByExactPhrase(query)
                : elasticsearchRepository.searchByTitleOrDescription(query);

        return processInChunksWithPermissions(stream, userId, resultLimit);
    }

    private Stream<Event> processInChunksWithPermissions(Stream<EventEntity> searchStream, String userId, int limit) {
        final int CHUNK_SIZE = 20; // Match WebFlux buffer size
        Iterator<EventEntity> iterator = searchStream.iterator();

        return Stream.generate(() -> {
                    // Collect next chunk from search results
                    List<EventEntity> chunk = new ArrayList<>(CHUNK_SIZE);
                    while (iterator.hasNext() && chunk.size() < CHUNK_SIZE) {
                        chunk.add(iterator.next());
                    }
                    return chunk;
                })
                .takeWhile(chunk -> !chunk.isEmpty()) // Stop when no more chunks
                .flatMap(chunk -> {
                    // Check permissions for this chunk
                    List<EventEntity> authorized = checkPermissionsBatch(chunk, userId);
                    return authorized.stream();
                })
                .map(EventMapper::toDto)
                .limit(limit); // Limit to desired number of results
    }

    /**
     * Checks permissions for a batch of entities.
     *
     * @param entities the batch of entities to check
     * @param userId the user ID to check permissions for
     * @return the filtered list of entities the user has permission to access
     */
    private List<EventEntity> checkPermissionsBatch(List<EventEntity> entities, String userId) {
        if (entities.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> eventIds = entities.stream()
                .map(EventEntity::getId)
                .collect(Collectors.toList());

        log.debug("Checking permissions for {} events, userId='{}'", eventIds.size(), userId);

        // Call authorization-service via REST API with the batch
        BatchPermissionCheckResponse response = authorizationClient.checkBatchPermissions(eventIds, userId);
        Set<Long> authorizedIds = response.authorizedEventIds();

        List<EventEntity> filtered = entities.stream()
                .filter(entity -> authorizedIds.contains(entity.getId()))
                .collect(Collectors.toList());

        log.debug("Filtered to {} authorized events for userId='{}'", filtered.size(), userId);
        return filtered;
    }

    public List<Event> getEventsForUser(String userId, int limit) {
        log.debug("Getting all events for userId='{}', limit={}", userId, limit);
        try {
            List<Long> eventIds = authorizationClient.getEventIdsForUser(userId);
            if (eventIds.isEmpty()) {
                return Collections.emptyList();
            }
            return StreamSupport.stream(elasticsearchRepository.findAllById(eventIds).spliterator(), false)
                    .limit(limit)
                    .map(EventMapper::toDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get events for userId='{}': {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    public UserPermissionsResponse getUserAuthorizedEventDetails(String userId) {
        List<Long> eventIds = authorizationClient.getEventIdsForUser(userId);
        return new UserPermissionsResponse(userId, eventIds.size(), eventIds);
    }
}
