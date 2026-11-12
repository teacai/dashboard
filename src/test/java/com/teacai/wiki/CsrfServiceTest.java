package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.service.CsrfService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.exceptions.HttpStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CsrfServiceTest {
    @Test void requiresMatchingSignedCookieAndFormTokenAndRejectsCrossSiteRequests() {
        var csrf = new CsrfService(new WikiProperties());
        var request = HttpRequest.GET("/");
        String token = csrf.token(request);
        assertThrows(HttpStatusException.class, () -> csrf.verify(request, token));
        request.cookie(Cookie.of("wiki-csrf", token));
        assertDoesNotThrow(() -> csrf.verify(request, token));
        assertEquals(token, csrf.token(request));
        assertThrows(HttpStatusException.class, () -> csrf.verify(request, token + "x"));
        request.header("Sec-Fetch-Site", "cross-site");
        assertThrows(HttpStatusException.class, () -> csrf.verify(request, token));
        request.header("Sec-Fetch-Site", "same-site");
        assertThrows(HttpStatusException.class, () -> csrf.verify(request, token));
    }

    @Test void forgedCookieAndTokensFromAnotherProcessAreInvalid() {
        var csrf = new CsrfService(new WikiProperties());
        String forged = "1234567890.nonce.forged";
        var request = HttpRequest.GET("/").cookie(Cookie.of("wiki-csrf", forged));
        assertThrows(HttpStatusException.class, () -> csrf.verify(request, forged));
        String token = csrf.token(request);
        request.cookie(Cookie.of("wiki-csrf", token));
        var restarted = new CsrfService(new WikiProperties());
        assertThrows(HttpStatusException.class, () -> restarted.verify(request, token));
    }

    @Test void supportsSecureCookiesBehindHttpsProxies() {
        var properties = new WikiProperties();
        properties.setSecureCookies(true);
        var csrf = new CsrfService(properties);
        var request = HttpRequest.GET("/");
        Cookie cookie = csrf.cookie(request, csrf.token(request));
        assertTrue(cookie.isSecure());
        assertTrue(cookie.isHttpOnly());
        assertEquals("__Host-wiki-csrf", cookie.getName());
    }
}
