package com.example.webfluxsse.authorization.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(
    basePackages = "com.example.webfluxsse.authorization.repository"
)
public class DatabaseConfig {
}
