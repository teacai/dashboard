package com.teacai.wiki;

import com.teacai.wiki.controller.PagePaths;
import io.micronaut.http.exceptions.HttpStatusException;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class PagePathsTest {
    @Test void canonicalizesOnlyUnreservedEscapes() {
        assertEquals("/a~b/%2F/%23/%3F", PagePaths.canonical(URI.create("/a%7eb/%2f/%23/%3f?q=ignored")));
        assertEquals("/%E6%97%A5", PagePaths.canonical(URI.create("/日")));
        assertEquals("/a//b/", PagePaths.canonical(URI.create("/a//b/")));
        assertEquals("/editor", PagePaths.editor("/"));
        assertEquals("/", PagePaths.page("/editor"));
        assertEquals("/a//editor", PagePaths.editor("/a/"));
        assertEquals("/.//evil.example", PagePaths.localUrl("//evil.example"));
    }

    @Test void rejectsControlCharactersDotSegmentsAndAssetNamespace() {
        for (String path : new String[]{"/a/%00", "/a/%0A", "/a/%7f", "/a/%2e%2e", "/_assets/anything"}) {
            assertThrows(HttpStatusException.class, () -> PagePaths.canonical(URI.create(path)));
        }
    }
}
