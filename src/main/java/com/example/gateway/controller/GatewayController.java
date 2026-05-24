package com.example.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Map;

/**
 * Controller exposing API Gateway core endpoints.
 * Demonstrates internal business logging and returns structured JSON responses.
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private static final Logger logger = LoggerFactory.getLogger(GatewayController.class);

    /**
     * Test endpoint to verify traffic management and rate limiting.
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> getTest() {
        logger.info("Processing /api/test request inside GatewayController.");
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Gateway integration test successful.",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Health check endpoint reflecting API status.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        logger.info("Processing /api/health check request.");
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "details", Map.of("rateLimiter", "active", "memoryUsage", "healthy"),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Status endpoint reporting gateway statistics.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        logger.info("Processing /api/status status metrics request.");
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        return ResponseEntity.ok(Map.of(
                "gatewayStatus", "active",
                "uptimeSeconds", uptimeMs / 1000,
                "jvmVersion", System.getProperty("java.version"),
                "timestamp", Instant.now().toString()
        ));
    }
}
