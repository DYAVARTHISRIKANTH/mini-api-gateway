package com.example.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the API Gateway Servlet Filter.
 * Utilizes MockMvc to mock HTTP requests and verify HTTP responses, headers,
 * and rate limiting rules under isolated IP addresses.
 */
@SpringBootTest(properties = {
        "gateway.rate-limiting.limit=3",
        "gateway.rate-limiting.window-seconds=10",
        "gateway.rate-limiting.cleanup-interval-seconds=60"
})
@AutoConfigureMockMvc
public class RateLimitingFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testAllowedRequestsHaveCorrelationHeaders() throws Exception {
        mockMvc.perform(get("/api/test")
                        .header("X-Forwarded-For", "192.168.5.10"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.message", is("Gateway integration test successful.")));
    }

    @Test
    void testRateLimiterBlocksAfterThresholdAndReturns429() throws Exception {
        String clientIp = "192.168.5.20";

        // First 3 requests must succeed
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/test")
                            .header("X-Forwarded-For", clientIp))
                    .andExpect(status().isOk());
        }

        // 4th request must fail with HTTP 429
        mockMvc.perform(get("/api/test")
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", is("10"))) // Since first call was just now, remaining wait is close to window size
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.error", is("Too Many Requests")))
                .andExpect(jsonPath("$.message", is("Rate limit exceeded. Please try again later.")))
                .andExpect(jsonPath("$.path", is("/api/test")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void testClientIpsAreIsolatedFromEachOther() throws Exception {
        String clientIpA = "192.168.5.30";
        String clientIpB = "192.168.5.40";

        // Exceed limit for IP A
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/status").header("X-Forwarded-For", clientIpA))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/status").header("X-Forwarded-For", clientIpA))
                .andExpect(status().isTooManyRequests());

        // IP B should still be allowed, fully unaffected by IP A's exhaust
        mockMvc.perform(get("/api/status").header("X-Forwarded-For", clientIpB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatewayStatus", is("active")));
    }

    @Test
    void testOtherEndpointsProtectedByLimiter() throws Exception {
        String clientIp = "192.168.5.50";

        mockMvc.perform(get("/api/health").header("X-Forwarded-For", clientIp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));

        mockMvc.perform(get("/api/health").header("X-Forwarded-For", clientIp))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/health").header("X-Forwarded-For", clientIp))
                .andExpect(status().isOk());
        
        // 4th request on /api/health gets blocked
        mockMvc.perform(get("/api/health").header("X-Forwarded-For", clientIp))
                .andExpect(status().isTooManyRequests());
    }
}
