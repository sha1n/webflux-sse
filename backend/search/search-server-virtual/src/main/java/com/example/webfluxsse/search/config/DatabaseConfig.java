package com.example.webfluxsse.search.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.example.webfluxsse.search.repository.jpa")
@EnableElasticsearchRepositories(basePackages = "com.example.webfluxsse.search.repository.elasticsearch")
public class DatabaseConfig {
}
