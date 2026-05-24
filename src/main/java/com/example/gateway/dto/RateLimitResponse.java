package com.example.gateway.dto;

/**
 * Immutable DTO representing the structured JSON payload returned when rate limit is exceeded (HTTP 429).
 * Implemented as a Java 17 record for cleaner, compiler-safe structure.
 */
public record RateLimitResponse(
        String timestamp,
        int status,
        String error,
        String message,
        String path
) {}
