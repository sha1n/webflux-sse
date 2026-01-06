package com.example.search.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request DTO for searching events.
 * Used by the RPC-style search endpoint.
 */
public record SearchRequest(
    @Schema(description = "Search query (searches in title and description). Leave null or empty to search all events.",
            example = "deployment",
            required = false)
    String query,

    @Schema(description = "User identifier for permission filtering",
            example = "user123",
            required = true)
    String userId,

    @Schema(description = "Maximum number of results to return (after authorization filtering). Defaults to 200.",
            example = "50",
            required = false)
    Integer limit
) {}
