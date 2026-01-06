package com.example.virtualthreads.search.repository.elasticsearch;

import com.example.virtualthreads.search.model.EventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.stream.Stream;

public interface EventElasticsearchRepository extends ElasticsearchRepository<EventEntity, Long> {

    /**
     * Full-text search across title and description fields using Elasticsearch multi_match query.
     * This properly handles spaces, special characters, and performs relevance-based scoring.
     * Uses pagination with search_after internally for stateless, memory-efficient iteration.
     *
     * @param query the search query text
     * @return stream of matching events ordered by relevance
     */
    @Query("""
            {
              "multi_match": {
                "query": "?0",
                "fields": ["title", "description"],
                "type": "best_fields",
                "operator": "or",
                "fuzziness": "AUTO"
              }
            }
            """)
    Stream<EventEntity> searchByTitleOrDescription(String query);

    /**
     * Exact phrase search across title and description fields using Elasticsearch multi_match with phrase type.
     * This matches the exact phrase in order, similar to Google's quoted search.
     * Uses pagination with search_after internally for stateless, memory-efficient iteration.
     *
     * @param phrase the exact phrase to search for
     * @return stream of matching events ordered by relevance
     */
    @Query("""
            {
              "multi_match": {
                "query": "?0",
                "fields": ["title", "description"],
                "type": "phrase"
              }
            }
            """)
    Stream<EventEntity> searchByExactPhrase(String phrase);

    Stream<EventEntity> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(String title, String description);

    Stream<EventEntity> findByTitleContainingIgnoreCase(String title);

    Stream<EventEntity> findByDescriptionContainingIgnoreCase(String description);
}
