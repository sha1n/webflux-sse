package com.example.webfluxsse.search.repository.elasticsearch;

import com.example.webfluxsse.search.model.EventEntity;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.stream.Stream;

public interface EventElasticsearchRepository extends ElasticsearchRepository<EventEntity, Long> {

    /**
     * Full-text search across title and description fields using Elasticsearch multi_match query.
     * This properly handles spaces, special characters, and performs relevance-based scoring.
     *
     * @param query the search query text
     * @return flux of matching events ordered by relevance
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
     *
     * @param phrase the exact phrase to search for
     * @return flux of matching events ordered by relevance
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
