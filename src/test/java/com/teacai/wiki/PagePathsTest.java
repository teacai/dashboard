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

    @Test void markdownRepresentationsResolveToStableHtmlPaths() {
        assertTrue(PagePaths.isMarkdown("/notes.md"));
        assertFalse(PagePaths.isMarkdown("/notes.md/editor"));
        assertFalse(PagePaths.isMarkdown("/notes.md/child"));
        assertEquals("/notes", PagePaths.htmlPage("/notes.md"));
        assertEquals("/notes", PagePaths.htmlPage("/notes.md.md"));
        assertEquals("/", PagePaths.htmlPage("/.md"));
        assertEquals("/notes/", PagePaths.htmlPage("/notes/.md"));
        assertEquals("/a%2Fb", PagePaths.htmlPage("/a%2Fb.md"));
        assertEquals("/notes.md/child", PagePaths.htmlPage("/notes.md/child"));
        for (String path : new String[]{"/_admin.md", "/_backup.md", "/_assets.md", "/_files.md", "/...md"}) {
            assertThrows(HttpStatusException.class, () -> PagePaths.htmlPage(path), path);
        }
    }

    @Test void markdownFilenamesUseTheFinalSlugWithARootFallback() {
        assertEquals("notes.md", PagePaths.markdownFilename("/nested/notes"));
        assertEquals("notes.md", PagePaths.markdownFilename("/nested/notes/"));
        assertEquals("index.md", PagePaths.markdownFilename("/"));
        assertEquals("a%2Fb.md", PagePaths.markdownFilename("/nested/a%2Fb"));
        assertEquals("%22%3B.md", PagePaths.markdownFilename("/nested/%22%3B"));
    }

    @Test void markdownAliasesRespectTheUnderlyingPageLengthLimit() {
        String path = "/" + "a".repeat(15999);
        assertEquals(path, PagePaths.htmlPage(PagePaths.canonical(URI.create(path + ".md"))));
        assertEquals(path + ".md/editor", PagePaths.canonical(URI.create(path + ".md/editor")));
        assertThrows(HttpStatusException.class, () -> PagePaths.canonical(URI.create(path + "a.md")));
    }

    @Test void rejectsControlCharactersDotSegmentsAndApplicationNamespaces() {
        for (String path : new String[]{"/a/%00", "/a/%0A", "/a/%7f", "/a/%2e%2e", "/_assets/anything",
                "/_admin", "/_admin/editor", "/_backup", "/_backup/editor", "/_%62ackup"}) {
            assertThrows(HttpStatusException.class, () -> PagePaths.canonical(URI.create(path)));
        }
    }
}
