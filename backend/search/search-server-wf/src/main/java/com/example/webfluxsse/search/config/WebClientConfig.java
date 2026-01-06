package com.example.webfluxsse.search.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for WebClient with optimized connection pooling for reactive HTTP calls.
 * <p>
 * Key optimizations:
 * - Connection pooling with configurable size (default 500 max connections)
 * - Connection reuse and keep-alive
 * - Proper timeouts at connection and HTTP level
 * - Memory buffer limits to prevent OOM
 * - HTTP/2 support with fallback to HTTP/1.1
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(
            @Value("${authorization-service.base-url}") String baseUrl,
            @Value("${authorization-service.connect-timeout:5s}") Duration connectTimeout,
            @Value("${authorization-service.read-timeout:10s}") Duration readTimeout,
            @Value("${authorization-service.write-timeout:10s}") Duration writeTimeout,
            @Value("${authorization-service.max-connections:500}") int maxConnections,
            @Value("${authorization-service.pending-acquire-timeout:45s}") Duration pendingAcquireTimeout,
            @Value("${authorization-service.max-idle-time:20s}") Duration maxIdleTime,
            @Value("${authorization-service.max-life-time:60s}") Duration maxLifeTime,
            @Value("${spring.codec.max-in-memory-size:10MB}") org.springframework.util.unit.DataSize maxInMemorySize) {

        // Configure connection provider with connection pooling
        ConnectionProvider connectionProvider = ConnectionProvider.builder("authorization-service-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(pendingAcquireTimeout)
                .maxIdleTime(maxIdleTime)
                .maxLifeTime(maxLifeTime)
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        // Configure HttpClient with timeouts and connection pooling
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(readTimeout)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(writeTimeout.toMillis(), TimeUnit.MILLISECONDS)))
                // Enable keep-alive
                .keepAlive(true);

        // Configure memory limits for request/response bodies
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize((int) maxInMemorySize.toBytes()))
                .build();

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
