package com.example.gateway.limiter;

import com.example.gateway.config.GatewayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SlidingWindowRateLimiter validating sliding window logic,
 * thread-safety, request thresholds, and stale-entry cleanup.
 */
public class SlidingWindowRateLimiterTest {

    private GatewayConfig config;
    private SlidingWindowRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        config = new GatewayConfig();
        config.setEnabled(true);
        config.setLimit(5);            // Max 5 requests
        config.setWindowSeconds(2);    // Within a 2-second sliding window
        config.setCleanupIntervalSeconds(1);
        rateLimiter = new SlidingWindowRateLimiter(config);
    }

    @Test
    void testRequestAllowedWithinLimit() {
        String ip = "192.168.1.100";
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.isAllowed(ip), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void testRequestBlockedExceedingLimit() {
        String ip = "192.168.1.101";
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.isAllowed(ip));
        }
        assertFalse(rateLimiter.isAllowed(ip), "6th request should be blocked");
    }

    @Test
    void testWindowSlidingAndRecovery() throws InterruptedException {
        String ip = "192.168.1.102";
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.isAllowed(ip));
        }
        assertFalse(rateLimiter.isAllowed(ip));

        // Sleep to let sliding window expire
        Thread.sleep(2100);

        assertTrue(rateLimiter.isAllowed(ip), "Should allow requests again after sliding window expiration");
    }

    @Test
    void testHighConcurrencyRateLimiting() throws InterruptedException {
        String ip = "192.168.1.103";
        config.setLimit(50);
        config.setWindowSeconds(10); // Safe window for test execution

        int threadCount = 10;
        int requestsPerThread = 10; // Total 100 requests. 50 should pass, 50 should be blocked.
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        
        AtomicInteger allowedRequests = new AtomicInteger(0);
        AtomicInteger blockedRequests = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        if (rateLimiter.isAllowed(ip)) {
                            allowedRequests.incrementAndGet();
                        } else {
                            blockedRequests.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Start all threads concurrently
        startLatch.countDown();
        
        // Wait for all threads to finish
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(50, allowedRequests.get(), "Exactly 50 requests should be allowed");
        assertEquals(50, blockedRequests.get(), "Exactly 50 requests should be blocked");
    }

    @Test
    void testLimiterCleanupRemovesStaleEntries() throws InterruptedException {
        String ip1 = "192.168.1.111";
        String ip2 = "192.168.1.112";

        assertTrue(rateLimiter.isAllowed(ip1));
        assertTrue(rateLimiter.isAllowed(ip2));
        
        // Ensure both IPs are tracked
        assertEquals(2, rateLimiter.getActiveIpCount());

        // Wait for window to expire
        Thread.sleep(2100);

        // Run cleanup manually (normally called by background Scheduler)
        rateLimiter.cleanupExpiredEntries();

        // Stale entries should be pruned since their queues are now empty
        assertEquals(0, rateLimiter.getActiveIpCount(), "Stale client IPs should be pruned from map");
    }
}
