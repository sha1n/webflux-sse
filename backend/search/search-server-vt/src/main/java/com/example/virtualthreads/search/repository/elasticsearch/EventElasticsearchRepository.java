package com.example.virtualthreads.search.repository.elasticsearch;

import com.example.virtualthreads.search.model.EventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface EventElasticsearchRepository extends ElasticsearchRepository<EventEntity, Long> {

    /**
     * Full-text search across title and description fields using Elasticsearch multi_match query.
     * This properly handles spaces, special characters, and performs relevance-based scoring.
     * Uses pagination with search_after internally for stateless, memory-efficient iteration.
     *
     * @param query the search query text
     * @param pageable pagination parameters (page size, sort)
     * @return page of matching events ordered by relevance
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
    Page<EventEntity> searchByTitleOrDescription(String query, Pageable pageable);

    /**
     * Exact phrase search across title and description fields using Elasticsearch multi_match with phrase type.
     * This matches the exact phrase in order, similar to Google's quoted search.
     * Uses pagination with search_after internally for stateless, memory-efficient iteration.
     *
     * @param phrase the exact phrase to search for
     * @param pageable pagination parameters (page size, sort)
     * @return page of matching events ordered by relevance
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
    Page<EventEntity> searchByExactPhrase(String phrase, Pageable pageable);

    Page<EventEntity> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(String title, String description, Pageable pageable);

    Page<EventEntity> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    Page<EventEntity> findByDescriptionContainingIgnoreCase(String description, Pageable pageable);
}
