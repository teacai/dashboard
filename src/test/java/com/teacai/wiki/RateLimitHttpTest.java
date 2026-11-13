package com.teacai.wiki;

import com.teacai.wiki.config.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@Property(name = "wiki.rate-limit.requests-per-minute", value = "4")
class RateLimitHttpTest {
    @Inject EmbeddedServer server;
    @Inject RateLimitProperties properties;
    @Inject MeterRegistry metrics;

    @Test void countsEveryMethodAndPathBeforeAuthenticationAndReturns429OnAllSubsequentRequests() throws Exception {
        assertEquals(4, properties.getRequestsPerMinute());
        try (var client = HttpClient.newHttpClient()) {
            // Successful, unsuccessful, static, and unsafe requests all consume the same IP budget.
            assertEquals(200, send(client, "GET", "/_assets/style.css", false).statusCode());
            assertEquals(401, send(client, "GET", "/_admin", false).statusCode());
            assertNotEquals(429, send(client, "POST", "/rate-limit-test", false).statusCode());
            assertEquals(200, send(client, "GET", "/rate-limit-test.md", false).statusCode());

            int rejected = 0;
            for (String method : new String[]{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "TRACE"}) {
                for (String path : new String[]{"/", "/_assets/editor.js", "/_admin", "/_backup", "/_files/missing", "/missing.md"}) {
                    var response = send(client, method, path, true);
                    assertEquals(429, response.statusCode(), method + " " + path + ": " + response.body());
                    int retryAfter = Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow());
                    assertTrue(retryAfter >= 1 && retryAfter <= 60);
                    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                    rejected++;
                }
            }
            assertEquals(rejected, metrics.get("wiki.requests.rate_limited").counter().count());
            assertEquals(1, metrics.get("wiki.rate_limit.tracked_ips").gauge().value());
        }
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, boolean spoofIp) throws Exception {
        var request = HttpRequest.newBuilder(server.getURI().resolve(path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (spoofIp) {
            request.header("X-Forwarded-For", "198.51.100." + (path.length() + method.length()))
                    .header("Forwarded", "for=203.0.113.1");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
