package com.example.gateway.filter;

import com.example.gateway.dto.RateLimitResponse;
import com.example.gateway.limiter.RateLimiter;
import com.example.gateway.util.IpExtractor;
import com.example.gateway.util.Sanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Custom servlet filter representing the API Gateway core entry point.
 * Intercepts all incoming requests to inject correlation IDs, enforce rate limiting,
 * track execution latencies, and output structured request logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info("Initializing Rate Limiting Filter...");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {

        if (!(servletRequest instanceof HttpServletRequest request) || !(servletResponse instanceof HttpServletResponse response)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        long startTime = System.nanoTime();

        // 1. Establish Correlation Tracking (MDC)
        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        correlationId = Sanitizer.sanitize(correlationId);
        MDC.put(CORRELATION_ID_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        // 2. Extract Client IP & Request Details securely
        String clientIp = IpExtractor.getClientIp(request);
        String method = Sanitizer.sanitize(request.getMethod());
        String uri = Sanitizer.sanitize(request.getRequestURI());

        logger.info("Incoming request: {} {} | Client IP: {}", method, uri, clientIp);

        // 3. Optional hook for authentication check (extension capability)
        if (!authenticate(request)) {
            logger.warn("Authentication failed for client IP: {} on URI: {}", clientIp, uri);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        try {
            // 4. Rate Limiting Check
            if (rateLimiter.isAllowed(clientIp)) {
                filterChain.doFilter(request, response);
                
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                logger.info("Request completed: {} {} | Status: {} | Latency: {} ms",
                        method, uri, response.getStatus(), durationMs);
            } else {
                long retryAfter = rateLimiter.getRetryAfterSeconds(clientIp);
                logger.warn("Rate limit exceeded for client IP: {} | Path: {} | Retry-After: {}s",
                        clientIp, uri, retryAfter);
                sendRateLimitError(response, uri, retryAfter);
            }
        } finally {
            // 5. Clean up thread-local MDC to prevent memory leaks in pooled containers
            MDC.remove(CORRELATION_ID_KEY);
        }
    }

    /**
     * Placeholder method for authentication check.
     * Can be extended to support API token validation, OAuth, or Session checks.
     */
    private boolean authenticate(HttpServletRequest request) {
        // Safe default: allow all for gateway demonstration.
        return true;
    }

    /**
     * Sends structured JSON payload when client exceeds their rate threshold.
     */
    private void sendRateLimitError(HttpServletResponse response, String path, long retryAfter) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfter));

        RateLimitResponse errorPayload = new RateLimitResponse(
                Instant.now().toString(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "Rate limit exceeded. Please try again later.",
                path
        );

        byte[] responseBytes = objectMapper.writeValueAsBytes(errorPayload);
        response.getOutputStream().write(responseBytes);
        response.getOutputStream().flush();
    }

    @Override
    public void destroy() {
        logger.info("Destroying Rate Limiting Filter...");
    }
}
