package com.example.gateway.limiter;

/**
 * Interface defining the API Gateway Rate Limiter contract.
 */
public interface RateLimiter {

    /**
     * Determines whether a request from the given client IP should be allowed.
     *
     * @param clientIp the IP address of the client
     * @return true if the request is within the allowed limits, false otherwise
     */
    boolean isAllowed(String clientIp);

    /**
     * Retrieves the remaining time in seconds until a blocked client can retry.
     *
     * @param clientIp the IP address of the client
     * @return the number of seconds to wait before a new request is allowed
     */
    long getRetryAfterSeconds(String clientIp);
}
