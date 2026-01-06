package com.example.virtualthreads.search.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.example.virtualthreads.search.repository.jpa")
@EnableElasticsearchRepositories(basePackages = "com.example.virtualthreads.search.repository.elasticsearch")
public class DatabaseConfig {
}
