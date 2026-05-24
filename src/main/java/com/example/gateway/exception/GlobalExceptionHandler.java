package com.example.gateway.exception;

import com.example.gateway.dto.RateLimitResponse;
import com.example.gateway.util.Sanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;

/**
 * Centralized controller advice to handle exceptions thrown during request processing.
 * Standardizes API error responses and enforces correct HTTP status codes.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles downstream rate limit exceptions (if thrown by controllers or aspects).
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<RateLimitResponse> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest request) {
        String path = Sanitizer.sanitize(request.getRequestURI());
        
        logger.warn("Rate limit exception caught in handler for client IP: {} | Path: {}", ex.getClientIp(), path);

        RateLimitResponse responseBody = new RateLimitResponse(
                Instant.now().toString(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                ex.getMessage(),
                path
        );

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));

        return new ResponseEntity<>(responseBody, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Handles all uncaught exceptions, preventing system details leakage and returning structured 500 errors.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RateLimitResponse> handleAllExceptions(Exception ex, HttpServletRequest request) {
        String path = Sanitizer.sanitize(request.getRequestURI());
        
        logger.error("Internal server error occurred on path: {} | Message: {}", path, ex.getMessage(), ex);

        RateLimitResponse responseBody = new RateLimitResponse(
                Instant.now().toString(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected error occurred. Please contact system administrator.",
                path
        );

        return new ResponseEntity<>(responseBody, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
