package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Singleton
public class AdminAuth {
    private static final Logger LOG = LoggerFactory.getLogger(AdminAuth.class);
    private final byte[] credentials;
    private final MeterRegistry metrics;

    public AdminAuth(WikiProperties properties, MeterRegistry metrics) {
        credentials = digest((properties.getAdminUsername() + ":" + properties.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        this.metrics = metrics;
        if (properties.getAdminUsername().equals("admin") && properties.getAdminPassword().equals("admin112")) {
            LOG.warn("Default backup administrator credentials are in use; change wiki.admin-password before deployment");
        }
    }

    public boolean authorized(HttpRequest<?> request) {
        String header = request.getHeaders().get("Authorization");
        if (header != null && header.length() <= 4096 && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                if (MessageDigest.isEqual(credentials, digest(Base64.getDecoder().decode(header.substring(6))))) return true;
            } catch (IllegalArgumentException ignored) { /* Malformed credentials are unauthorized, not server errors. */ }
        }
        metrics.counter("wiki.admin.authentication", "result", "failure").increment();
        return false;
    }

    @Nullable
    public HttpResponse<?> check(HttpRequest<?> request) {
        if (!authorized(request)) return unauthorized();
        if (request.getMethod() == HttpMethod.POST) {
            // Cached Basic credentials must not authorize a cross-site browser form submission.
            String site = request.getHeaders().get("Sec-Fetch-Site");
            if (request.getHeaders().contains("Origin") || site != null && !site.equals("none")) {
                return HttpResponse.status(HttpStatus.FORBIDDEN).body("Restore requires a non-browser API request without an Origin header.");
            }
        }
        return null;
    }

    public HttpResponse<?> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Basic realm=\"Wiki backup administrator\", charset=\"UTF-8\"")
                .header("Cache-Control", "no-store");
    }

    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
