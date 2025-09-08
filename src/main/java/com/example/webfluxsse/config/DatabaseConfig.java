package com.example.webfluxsse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableReactiveElasticsearchRepositories;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(
    basePackages = "com.example.webfluxsse.repository.r2dbc"
)
public class DatabaseConfig {
    
    @Configuration
    @ConditionalOnProperty(name = "spring.elasticsearch.uris")
    @EnableReactiveElasticsearchRepositories(
        basePackages = "com.example.webfluxsse.repository.elasticsearch"
    )
    static class ElasticsearchConfig {
    }
}