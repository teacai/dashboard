package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.controller.BackupAuthFilter;
import com.teacai.wiki.service.AdminAuth;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminAccessFilterTest {
    private String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test void protectsBothNamespacesAndEncodedAliasesForEveryHttpMethod() {
        var metrics = new SimpleMeterRegistry();
        try {
            var properties = new WikiProperties();
            properties.setAdminUsername("operator");
            properties.setAdminPassword("configured-password");
            var filter = new BackupAuthFilter(new AdminAuth(properties, metrics));
            for (String path : List.of("/_admin", "/_admin/", "/_admin?restored=true", "/_admin/restore", "/_admin/unknown",
                    "/_%61dmin", "/%5Fadmin/restore", "/_backup", "/_backup/", "/_backup?download=true",
                    "/_backup/editor", "/_%62ackup", "/_backup%2Funknown")) {
                for (var method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS)) {
                    for (String header : new String[]{null, "Basic malformed", "Bearer configured-password", basic("admin:admin112"), basic("operator:wrong")}) {
                        var request = HttpRequest.create(method, path);
                        if (header != null) request.header("Authorization", header);
                        var response = filter.authenticate(request);
                        assertNotNull(response, method + " " + path);
                        assertEquals(401, response.code(), method + " " + path);
                        assertTrue(response.getHeaders().get("WWW-Authenticate").startsWith("Basic "));
                    }
                    assertNull(filter.authenticate(HttpRequest.create(method, path)
                            .header("Authorization", basic("operator:configured-password"))), method + " " + path);
                }
            }
            for (String path : List.of("/", "/editor", "/_assets/style.css", "/_admin-notes", "/_backup-notes", "/notes?next=/_admin")) {
                assertNull(filter.authenticate(HttpRequest.GET(path)), path);
            }
        } finally { metrics.close(); }
    }

    @Test void defaultCredentialsWorkAndApiBrowserPostProtectionRemainsEnabled() {
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new BackupAuthFilter(new AdminAuth(new WikiProperties(), metrics));
            for (String path : List.of("/_admin", "/_backup")) {
                assertNull(filter.authenticate(HttpRequest.GET(path).header("Authorization", basic("admin:admin112"))));
            }
            var request = HttpRequest.POST("/_backup", "archive")
                    .header("Authorization", basic("admin:admin112")).header("Origin", "https://other.example");
            assertEquals(403, filter.authenticate(request).code());
        } finally { metrics.close(); }
    }
}
