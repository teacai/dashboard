package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/** Signed double-submit tokens. No server sessions; tokens expire after 8 hours or restart. */
@Singleton
public class CsrfService {
    private static final long TTL = 8 * 60 * 60;
    private final byte[] key = new byte[32];
    private final SecureRandom random = new SecureRandom();
    private final boolean secureCookies;

    public CsrfService(WikiProperties properties) {
        secureCookies = properties.isSecureCookies();
        random.nextBytes(key);
    }

    public String token(HttpRequest<?> request) {
        String current = request.getCookies().findCookie(cookieName(request)).map(Cookie::getValue).orElse("");
        if (valid(current)) return current;
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String payload = Instant.now().getEpochSecond() + "." + encode(nonce);
        return payload + "." + sign(payload);
    }

    public Cookie cookie(HttpRequest<?> request, String token) {
        return Cookie.of(cookieName(request), token).path("/").httpOnly(true).secure(secureCookies || request.isSecure())
                .sameSite(SameSite.Strict).maxAge(TTL);
    }

    public void verify(HttpRequest<?> request, String token) {
        String cookie = request.getCookies().findCookie(cookieName(request)).map(Cookie::getValue).orElse("");
        String fetchSite = request.getHeaders().get("Sec-Fetch-Site");
        boolean foreignSite = fetchSite != null && !fetchSite.equals("same-origin") && !fetchSite.equals("none");
        if (token == null || !valid(token) || !equal(cookie, token) || foreignSite) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Invalid or expired editor token. Reload the editor.");
        }
    }

    private String cookieName(HttpRequest<?> request) {
        // Browsers prohibit Domain cookies with __Host-, blocking sibling-subdomain cookie injection.
        return secureCookies || request.isSecure() ? "__Host-wiki-csrf" : "wiki-csrf";
    }

    private boolean valid(String token) {
        if (token == null || token.length() > 200) return false;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        try {
            long age = Instant.now().getEpochSecond() - Long.parseLong(parts[0]);
            return age >= 0 && age <= TTL && equal(sign(parts[0] + "." + parts[1]), parts[2]);
        } catch (NumberFormatException e) { return false; }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return encode(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private boolean equal(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
