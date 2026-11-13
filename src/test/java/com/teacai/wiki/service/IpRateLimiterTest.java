package com.teacai.wiki.service;

import com.teacai.wiki.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class IpRateLimiterTest {
    private final AtomicLong now = new AtomicLong();

    private IpRateLimiter limiter(int limit) {
        var properties = new RateLimitProperties();
        properties.setRequestsPerMinute(limit);
        return new IpRateLimiter(properties, now::get);
    }

    @Test void defaultsToSixtyAndRejectsInvalidLimits() {
        var properties = new RateLimitProperties();
        assertEquals(60, properties.getRequestsPerMinute());
        assertThrows(IllegalArgumentException.class, () -> properties.setRequestsPerMinute(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setRequestsPerMinute(-1));
        properties.setRequestsPerMinute(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, properties.getRequestsPerMinute());
    }

    @Test void allowsExactlyTheLimitAndKeepsIpsIndependent() {
        var limiter = limiter(60);
        for (int i = 0; i < 60; i++) assertTrue(limiter.acquire("192.0.2.1").allowed());
        assertEquals(new IpRateLimiter.Decision(false, 60), limiter.acquire("192.0.2.1"));
        assertFalse(limiter.acquire("192.0.2.1").allowed());
        assertTrue(limiter.acquire("192.0.2.2").allowed());
        assertTrue(limiter.acquire("2001:db8::1").allowed());
        assertEquals(3, limiter.trackedIpCount());
    }

    @Test void roundsRetryAfterUpAndResetsAtTheExactBoundaryWithoutExtendingOnRejection() {
        var limiter = limiter(1);
        assertTrue(limiter.acquire("ip").allowed());
        now.set(TimeUnit.MILLISECONDS.toNanos(500));
        assertEquals(60, limiter.acquire("ip").retryAfterSeconds());
        now.set(TimeUnit.SECONDS.toNanos(1));
        assertEquals(59, limiter.acquire("ip").retryAfterSeconds());
        now.set(TimeUnit.MINUTES.toNanos(1) - 1);
        assertEquals(new IpRateLimiter.Decision(false, 1), limiter.acquire("ip"));
        now.incrementAndGet();
        assertTrue(limiter.acquire("ip").allowed());
        assertEquals(new IpRateLimiter.Decision(false, 60), limiter.acquire("ip"));
    }

    @Test void evictsExpiredEntriesButRetainsActiveWindows() {
        var limiter = limiter(1);
        limiter.acquire("old");
        now.set(TimeUnit.SECONDS.toNanos(30));
        limiter.acquire("active");
        now.set(TimeUnit.MINUTES.toNanos(1));
        limiter.evictExpired();
        assertEquals(1, limiter.trackedIpCount());
        assertFalse(limiter.acquire("active").allowed());
        assertTrue(limiter.acquire("old").allowed());
        now.set(TimeUnit.MINUTES.toNanos(2));
        limiter.evictExpired();
        assertEquals(0, limiter.trackedIpCount());
    }

    @Test void startsEachIpsWindowWithItsFirstRequestAndHandlesMonotonicClockWraparound() {
        now.set(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(20));
        var limiter = limiter(1);
        assertTrue(limiter.acquire("ip").allowed());
        now.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertEquals(new IpRateLimiter.Decision(false, 1), limiter.acquire("ip"));
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(limiter.acquire("ip").allowed());
    }

    @Test void concurrentRequestsCannotOverspendTheLimit() throws Exception {
        var limiter = limiter(60);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 500; i++) {
                results.add(executor.submit(() -> limiter.acquire("same-ip").allowed()));
            }
            int allowed = 0;
            for (var result : results) if (result.get()) allowed++;
            assertEquals(60, allowed);
            assertEquals(1, limiter.trackedIpCount());
        }
    }
}
