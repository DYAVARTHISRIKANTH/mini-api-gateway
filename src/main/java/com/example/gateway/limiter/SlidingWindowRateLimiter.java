package com.example.gateway.limiter;

import com.example.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * High-performance, thread-safe implementation of a Sliding Window Rate Limiter.
 * Uses ConcurrentHashMap and ConcurrentLinkedDeque to track request timestamps per client IP.
 * Fine-grained per-IP synchronization ensures safety under high concurrency with low lock contention.
 */
@Component
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    private final GatewayConfig config;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> clientRequestMap = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(GatewayConfig config) {
        this.config = config;
    }

    @Override
    public boolean isAllowed(String clientIp) {
        if (!config.isEnabled()) {
            return true;
        }

        long now = System.currentTimeMillis();
        long windowMillis = config.getWindowSeconds() * 1000L;
        long boundary = now - windowMillis;

        // Retrieve or initialize the queue for the client IP atomically
        ConcurrentLinkedDeque<Long> timestamps = clientRequestMap.computeIfAbsent(clientIp, k -> new ConcurrentLinkedDeque<>());

        // Synchronize on the deque to ensure atomic read-modify-write operations for this client IP
        synchronized (timestamps) {
            // Remove timestamps older than the sliding window boundary
            while (!timestamps.isEmpty() && timestamps.peekFirst() < boundary) {
                timestamps.pollFirst();
            }

            // Check if the number of requests in the sliding window exceeds the configured limit
            if (timestamps.size() < config.getLimit()) {
                timestamps.addLast(now);
                return true;
            } else {
                logger.warn("Rate limit exceeded for client IP: {}. Current requests in window: {}", clientIp, timestamps.size());
                return false;
            }
        }
    }

    @Override
    public long getRetryAfterSeconds(String clientIp) {
        ConcurrentLinkedDeque<Long> timestamps = clientRequestMap.get(clientIp);
        if (timestamps == null) {
            return 0L;
        }

        long now = System.currentTimeMillis();
        long windowMillis = config.getWindowSeconds() * 1000L;

        synchronized (timestamps) {
            Long oldestTimestamp = timestamps.peekFirst();
            if (oldestTimestamp == null) {
                return 0L;
            }
            long timeElapsedSinceOldest = now - oldestTimestamp;
            long timeRemaining = windowMillis - timeElapsedSinceOldest;
            return Math.max(1L, (long) Math.ceil(timeRemaining / 1000.0));
        }
    }

    /**
     * Periodically cleans up empty or completely expired client IP entries to prevent memory leaks.
     * Runs in a background thread based on application configuration scheduler.
     */
    @Scheduled(fixedDelayString = "${gateway.rate-limiting.cleanup-interval-seconds:30}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void cleanupExpiredEntries() {
        if (!config.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long windowMillis = config.getWindowSeconds() * 1000L;
        long boundary = now - windowMillis;

        int removedCount = 0;

        for (String ip : clientRequestMap.keySet()) {
            ConcurrentLinkedDeque<Long> timestamps = clientRequestMap.get(ip);
            if (timestamps != null) {
                synchronized (timestamps) {
                    // Remove expired timestamps from the head
                    while (!timestamps.isEmpty() && timestamps.peekFirst() < boundary) {
                        timestamps.pollFirst();
                    }
                    // If the deque is now empty, remove the IP entry from the map
                    if (timestamps.isEmpty()) {
                        if (clientRequestMap.remove(ip, timestamps)) {
                            removedCount++;
                        }
                    }
                }
            }
        }

        if (removedCount > 0) {
            logger.info("Background rate limiter cleanup complete. Removed {} idle client IP entries. Active IPs: {}",
                    removedCount, clientRequestMap.size());
        }
    }

    /**
     * Visible for testing to check current count of keys in map.
     */
    public int getActiveIpCount() {
        return clientRequestMap.size();
    }
}
