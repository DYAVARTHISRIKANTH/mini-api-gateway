package com.example.gateway.exception;

/**
 * Custom runtime exception representing a rate limiting threshold breach.
 * Useful if rate limiting is applied via AOP or downstream controllers.
 */
public class RateLimitExceededException extends RuntimeException {

    private final String clientIp;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String clientIp, long retryAfterSeconds, String message) {
        super(message);
        this.clientIp = clientIp;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getClientIp() {
        return clientIp;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
