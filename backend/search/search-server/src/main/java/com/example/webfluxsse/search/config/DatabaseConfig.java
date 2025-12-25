package com.example.webfluxsse.search.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.elasticsearch.repository.config.EnableReactiveElasticsearchRepositories;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(
    basePackages = "com.example.webfluxsse.search.repository.r2dbc"
)
public class DatabaseConfig {

    @Configuration
    @ConditionalOnProperty(name = "spring.elasticsearch.uris")
    @EnableReactiveElasticsearchRepositories(
        basePackages = "com.example.webfluxsse.search.repository.elasticsearch",
        excludeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com.example.webfluxsse.search.repository.r2dbc.*"
        )
    )
    static class ElasticsearchConfig {
    }
}
