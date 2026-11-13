package com.teacai.wiki.controller;

import com.teacai.wiki.service.IpRateLimiter;
import com.teacai.wiki.service.RateLimitClientIp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.server.annotation.PreMatching;

/** Counts every request before authentication, maintenance leases, or body binding. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ServerFilter("/**")
public class RateLimitFilter {
    private final IpRateLimiter limiter;
    private final RateLimitClientIp clientIp;
    private final Counter rejected;

    public RateLimitFilter(IpRateLimiter limiter, RateLimitClientIp clientIp, MeterRegistry metrics) {
        this.limiter = limiter;
        this.clientIp = clientIp;
        rejected = metrics.counter("wiki.requests.rate_limited");
        metrics.gauge("wiki.rate_limit.tracked_ips", limiter, IpRateLimiter::trackedIpCount);
    }

    @PreMatching
    @RequestFilter
    @Nullable
    public HttpResponse<?> admit(HttpRequest<?> request) {
        var decision = limiter.acquire(clientIp.resolve(request));
        if (decision.allowed()) return null;
        rejected.increment();
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Integer.toString(decision.retryAfterSeconds()))
                .header("Cache-Control", "no-store");
    }
}
