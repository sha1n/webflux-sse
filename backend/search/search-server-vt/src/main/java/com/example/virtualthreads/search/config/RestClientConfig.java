package com.example.virtualthreads.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Configuration for RestClient with optimized connection pooling for Virtual Threads.
 * <p>
 * Key optimizations:
 * - Uses JDK HttpClient with connection pooling
 * - Configures connect timeout to fail fast
 * - Uses virtual thread executor for non-blocking I/O operations
 * - Enables HTTP/2 for better performance
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient(
            @Value("${authorization-service.base-url}") String baseUrl,
            @Value("${authorization-service.connect-timeout:5s}") Duration connectTimeout,
            @Value("${authorization-service.http-version:HTTP_2}") String httpVersion) {

        // Parse HTTP version (HTTP_1_1 or HTTP_2)
        HttpClient.Version version;
        try {
            version = HttpClient.Version.valueOf(httpVersion);
        } catch (IllegalArgumentException e) {
            version = HttpClient.Version.HTTP_2; // Default to HTTP/2
        }

        // Configure JDK HttpClient with connection pooling and virtual threads
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Use virtual thread executor for async operations
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                // Enable HTTP/2 by default (falls back to HTTP/1.1 if not supported)
                // Can be overridden via authorization-service.http-version property
                .version(version)
                .build();

        // Create RestClient with optimized request factory
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }
}
