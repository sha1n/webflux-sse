package com.example.webfluxsse.search.repository.elasticsearch;

import com.example.webfluxsse.search.model.EventEntity;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository;
import reactor.core.publisher.Flux;

public interface EventElasticsearchRepository extends ReactiveElasticsearchRepository<EventEntity, Long> {

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
    Flux<EventEntity> searchByTitleOrDescription(String query);

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
    Flux<EventEntity> searchByExactPhrase(String phrase);

    Flux<EventEntity> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(String title, String description);

    Flux<EventEntity> findByTitleContainingIgnoreCase(String title);

    Flux<EventEntity> findByDescriptionContainingIgnoreCase(String description);
}
