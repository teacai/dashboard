package com.teacai.wiki;

import com.teacai.wiki.config.RateLimitProperties;
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
@Property(name = "wiki.rate-limit.requests-per-minute", value = "2")
@Property(name = "wiki.rate-limit.trust-forwarded-headers", value = "true")
class TrustedRateLimitHttpTest {
    @Inject EmbeddedServer server;
    @Inject RateLimitProperties properties;

    @Test void tracksForwardedClientsIndependentlyAndFallsBackToTheConnectionIp() throws Exception {
        assertTrue(properties.isTrustForwardedHeaders());
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(200, send(client, "X-Forwarded-For", "198.51.100.1, 192.0.2.1"));
            assertEquals(200, send(client, "X-Forwarded-For", "198.51.100.1:1234"));
            assertEquals(429, send(client, "X-Forwarded-For", "198.51.100.1, 192.0.2.2"));
            assertEquals(200, send(client, "X-Forwarded-For", "198.51.100.2"));

            assertEquals(200, send(client, "Forwarded", "for=198.51.100.3;proto=https",
                    "X-Forwarded-For", "198.51.100.1"));
            assertEquals(200, send(client, "Forwarded", "for=\"198.51.100.3:443\", for=192.0.2.1"));
            assertEquals(429, send(client, "Forwarded", "for=198.51.100.3"));

            assertEquals(200, send(client, "Forwarded", "for=\"[2001:db8::1]:1234\""));
            assertEquals(200, send(client, "X-Forwarded-For", "2001:0db8:0:0:0:0:0:1"));
            assertEquals(429, send(client, "Forwarded", "for=\"[2001:db8::1]\""));

            assertEquals(200, send(client));
            assertEquals(200, send(client, "Forwarded", "for=unknown"));
            assertEquals(429, send(client, "X-Forwarded-For", "not-an-ip"));
        }
    }

    private int send(HttpClient client, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(server.getURI().resolve("/_assets/style.css")).GET();
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
