package com.teacai.wiki.service;

import com.teacai.wiki.config.RateLimitProperties;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Per-IP fixed windows starting with the first request, using monotonic time. */
@Singleton
public class IpRateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(IpRateLimiter.class);
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Decision ALLOWED = new Decision(true, 0);
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final LongSupplier ticker;

    @Inject
    public IpRateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    IpRateLimiter(RateLimitProperties properties, LongSupplier ticker) {
        this.limit = properties.getRequestsPerMinute();
        this.ticker = ticker;
    }

    public Decision acquire(String ip) {
        // Read time inside compute so simultaneous requests cannot apply timestamps out of order.
        Window window = windows.compute(ip, (key, previous) -> {
            long now = ticker.getAsLong();
            if (previous == null || now - previous.startedAt() >= WINDOW_NANOS) return new Window(now, 1);
            if (previous.requests() > limit) return previous;
            if (previous.requests() == limit) {
                // Log only the first rejection per IP/window; never use IPs as metric tags.
                LOG.warn("Per-IP request rate limit exceeded: limit={} requests/minute", limit);
            }
            return new Window(previous.startedAt(), previous.requests() + 1);
        });
        if (window.requests() <= limit) return ALLOWED;
        long remaining = WINDOW_NANOS - (ticker.getAsLong() - window.startedAt());
        int retryAfter = (int) Math.max(1, (remaining + SECOND_NANOS - 1) / SECOND_NANOS);
        return new Decision(false, retryAfter);
    }

    @Scheduled(fixedDelay = "1m", initialDelay = "1m")
    public void evictExpired() {
        long now = ticker.getAsLong();
        // Immutable values allow conditional removal without deleting a concurrently renewed window.
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_NANOS);
    }

    public int trackedIpCount() { return windows.size(); }

    public record Decision(boolean allowed, int retryAfterSeconds) {}
    private record Window(long startedAt, long requests) {}
}
