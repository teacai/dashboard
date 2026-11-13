package com.teacai.wiki;

import com.teacai.wiki.config.RateLimitProperties;
import com.teacai.wiki.controller.RateLimitFilter;
import com.teacai.wiki.service.IpRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpRequestWrapper;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {
    private HttpRequest<?> request(String ip, int port) {
        return new HttpRequestWrapper<>(HttpRequest.GET("/_assets/style.css")
                .header("X-Forwarded-For", "198.51.100." + port)
                .header("Forwarded", "for=198.51.100." + port)) {
            @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(ip, port); }
        };
    }

    @Test void usesConnectionIpNotPortOrForwardingHeadersAndExposesLowCardinalityMetrics() {
        var properties = new RateLimitProperties();
        properties.setRequestsPerMinute(1);
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new RateLimitFilter(new IpRateLimiter(properties), metrics);
            assertNull(filter.admit(request("192.0.2.1", 1000)));
            var rejection = filter.admit(request("192.0.2.1", 2000));
            assertNotNull(rejection);
            assertEquals(429, rejection.code());
            assertEquals("no-store", rejection.header("Cache-Control"));
            assertTrue(Integer.parseInt(rejection.header("Retry-After")) > 0);
            assertNull(filter.admit(request("192.0.2.2", 1000)));
            assertNull(filter.admit(request("2001:db8::1", 1000)));
            assertEquals(429, filter.admit(request("2001:db8::1", 2000)).code());
            assertEquals(2, metrics.get("wiki.requests.rate_limited").counter().count());
            assertEquals(3, metrics.get("wiki.rate_limit.tracked_ips").gauge().value());
            assertTrue(metrics.get("wiki.requests.rate_limited").counter().getId().getTags().isEmpty());
        } finally { metrics.close(); }
    }
}
