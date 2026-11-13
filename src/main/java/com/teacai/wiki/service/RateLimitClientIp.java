package com.teacai.wiki.service;

import com.teacai.wiki.config.RateLimitProperties;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.util.ProxyHeaderParser;
import jakarta.inject.Singleton;

import java.net.InetAddress;

/** Forwarding headers are trusted only when a sanitizing reverse proxy is explicitly configured. */
@Singleton
public class RateLimitClientIp {
    private final boolean trustForwardedHeaders;

    public RateLimitClientIp(RateLimitProperties properties) {
        trustForwardedHeaders = properties.isTrustForwardedHeaders();
    }

    public String resolve(HttpRequest<?> request) {
        if (trustForwardedHeaders) {
            try {
                // Micronaut gives Forwarded precedence over X-Forwarded-For and preserves chain order.
                var addresses = new ProxyHeaderParser(request).getFor();
                if (!addresses.isEmpty()) {
                    String ip = normalize(addresses.getFirst());
                    if (ip != null) return ip;
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
                // Malformed proxy headers must not fail the request or create arbitrary bucket keys.
            }
        }
        var remote = request.getRemoteAddress();
        return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
    }

    @Nullable
    private static String normalize(String value) {
        String ip = value.trim();
        if (ip.startsWith("\"") && ip.endsWith("\"") && ip.length() >= 2) {
            ip = ip.substring(1, ip.length() - 1);
        }
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end < 0 || !validPortSuffix(ip.substring(end + 1))) return null;
            ip = ip.substring(1, end);
        } else {
            // A single colon separates an IPv4 address from a port; bare IPv6 has multiple colons.
            int colon = ip.indexOf(':');
            if (colon >= 0 && colon == ip.lastIndexOf(':')) {
                if (!validPortSuffix(ip.substring(colon))) return null;
                ip = ip.substring(0, colon);
            }
        }
        // Do not resolve hostnames or scoped addresses through DNS/network-interface lookups.
        if (ip.contains("%")) return null;
        try {
            return InetAddress.ofLiteral(ip).getHostAddress();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean validPortSuffix(String suffix) {
        if (suffix.isEmpty()) return true;
        if (suffix.charAt(0) != ':' || suffix.length() < 2 || suffix.length() > 6) return false;
        for (int i = 1; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return false;
        }
        return Integer.parseInt(suffix.substring(1)) <= 65535;
    }
}
