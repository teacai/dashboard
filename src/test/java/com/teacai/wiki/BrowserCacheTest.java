package com.teacai.wiki;

import com.teacai.wiki.http.BrowserCache;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserCacheTest {
    @Test void hashesBytesIntoStableQuotedValidators() {
        String etag = BrowserCache.etag("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\"", etag);
        assertNotEquals(etag, BrowserCache.etag("HELLO".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void matchesWeakAndStrongValidatorsListsRepeatedHeadersAndWildcard() {
        String etag = "\"current\"";
        assertTrue(BrowserCache.matches(List.of(etag), etag));
        assertTrue(BrowserCache.matches(List.of("W/" + etag), etag));
        assertTrue(BrowserCache.matches(List.of(etag), "W/" + etag));
        assertTrue(BrowserCache.matches(List.of("\"old\", W/\"current\""), etag));
        assertTrue(BrowserCache.matches(List.of("\"old\"", " \"current\" "), etag));
        assertTrue(BrowserCache.matches(List.of("*"), etag));
        assertFalse(BrowserCache.matches(List.of(), etag));
        assertFalse(BrowserCache.matches(List.of("\"other\""), etag));
        assertFalse(BrowserCache.matches(List.of("current", "", "w/\"current\""), etag));
    }
}
