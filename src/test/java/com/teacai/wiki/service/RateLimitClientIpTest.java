package com.teacai.wiki.service;

import com.teacai.wiki.config.RateLimitProperties;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpRequestWrapper;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitClientIpTest {
    private RateLimitClientIp resolver(boolean trusted) {
        var properties = new RateLimitProperties();
        properties.setTrustForwardedHeaders(trusted);
        return new RateLimitClientIp(properties);
    }

    private HttpRequest<?> request(String... headers) {
        var request = HttpRequest.GET("/");
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return new HttpRequestWrapper<>(request) {
            @Override public InetSocketAddress getRemoteAddress() {
                return new InetSocketAddress("192.0.2.10", 12345);
            }
        };
    }

    @Test void ignoresForwardingHeadersByDefault() {
        var resolver = new RateLimitClientIp(new RateLimitProperties());
        assertEquals("192.0.2.10", resolver.resolve(request(
                "Forwarded", "for=198.51.100.1", "X-Forwarded-For", "203.0.113.1")));
    }

    @Test void trustsFirstClientInTheChainAndPrefersForwarded() {
        var resolver = resolver(true);
        assertEquals("198.51.100.1", resolver.resolve(request(
                "Forwarded", "For=198.51.100.1;proto=https, for=192.0.2.1",
                "X-Forwarded-For", "203.0.113.1")));
        assertEquals("198.51.100.1", resolver.resolve(request(
                "X-Forwarded-For", "198.51.100.1,  192.0.2.1")));
        assertEquals("198.51.100.1", resolver.resolve(request(
                "Forwarded", "for=198.51.100.1", "Forwarded", "for=192.0.2.1")));
    }

    @Test void stripsPortsAndCanonicalizesIpv4AndIpv6WithoutDnsLookups() {
        var resolver = resolver(true);
        assertEquals("198.51.100.1", resolver.resolve(request("Forwarded", "for=\"198.51.100.1:54321\"")));
        assertEquals("198.51.100.1", resolver.resolve(request("X-Forwarded-For", "198.51.100.1:443")));
        String ipv6 = "2001:db8:0:0:0:0:0:1";
        assertEquals(ipv6, resolver.resolve(request("Forwarded", "for=\"[2001:db8::1]:443\";proto=https")));
        assertEquals(ipv6, resolver.resolve(request("X-Forwarded-For", "2001:db8::1")));
        assertEquals(ipv6, resolver.resolve(request("X-Forwarded-For", "[2001:0db8:0:0:0:0:0:1]")));
    }

    @Test void missingInvalidObfuscatedOrScopedAddressesFallBackToTheConnectionIp() {
        var resolver = resolver(true);
        assertEquals("192.0.2.10", resolver.resolve(request()));
        for (String invalid : new String[]{"", "unknown", "_hidden", "localhost", "example.com", "999.1.1.1",
                "[2001:db8::1", "[2001:db8::1]:65536", "198.51.100.1:bad", "198.51.100.1:-1",
                "[fe80::1%lo0]", "\"", "\"198.51.100.1", "unknown, 198.51.100.1"}) {
            assertEquals("192.0.2.10", resolver.resolve(request("X-Forwarded-For", invalid)), invalid);
            assertEquals("192.0.2.10", resolver.resolve(request("Forwarded", "for=" + invalid)), invalid);
        }
        // An invalid preferred header must not silently switch to another header or a later proxy.
        assertEquals("192.0.2.10", resolver.resolve(request(
                "Forwarded", "for=unknown, for=198.51.100.1", "X-Forwarded-For", "203.0.113.1")));
        assertEquals("192.0.2.10", resolver.resolve(request(
                "Forwarded", "proto=https", "X-Forwarded-For", "203.0.113.1")));
    }
}
