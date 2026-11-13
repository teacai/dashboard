package com.teacai.wiki;

import com.teacai.wiki.model.Page;
import com.teacai.wiki.repository.PageRepository;
import com.teacai.wiki.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@Property(name = "wiki.cache-max-size-mb", value = "2")
@Property(name = "wiki.cache-max-file-size-mb", value = "1")
class ContentCacheHttpTest {
    @Inject EmbeddedServer server;
    @Inject ContentCache cache;
    @Inject PageService pages;
    @Inject PageRepository repository;
    @Inject FileService files;
    @Inject MeterRegistry metrics;
    private HttpTestSupport http;

    @BeforeEach void open() {
        assertNotNull(cache.get("page:/", PageService.RenderedPage.class), "Home must be warm before any request");
        pages.resetCache();
        http = new HttpTestSupport(server.getURI());
    }
    @AfterEach void close() { http.close(); }
    private String path() { return "/cache/" + UUID.randomUUID(); }
    private long renders() { return metrics.timer("wiki.markdown.render").count(); }
    private long reads() { return metrics.timer("wiki.database.read").count(); }
    private AttachmentStorage.Source source(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new AttachmentStorage.Source("file.txt", bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    @Test void cachesSanitizedHtmlOnFirstReadAndReusesItForSubsequentRequests() throws Exception {
        String path = path();
        String content = "# Cached\n\n**bold** 日本\n\n<script>alert(1)</script>";
        assertTrue(repository.save(new Page(path, content, null, -1), -1));
        long beforeRenders = renders(), beforeReads = reads();
        String body = http.get(path).body();
        assertTrue(body.contains("<h1>Cached</h1>"));
        assertTrue(body.contains("<strong>bold</strong>"));
        assertFalse(body.contains("<script>alert"));
        var cached = cache.get("page:" + path, PageService.RenderedPage.class);
        assertNotNull(cached);
        assertEquals(content, cached.page().content());
        for (int i = 0; i < 3; i++) {
            assertEquals(body, http.get(path).body());
            assertEquals(content, http.get(path + ".md").body());
            assertEquals(content, http.editor(path).get("content"));
        }
        assertEquals(beforeRenders + 1, renders());
        assertEquals(beforeReads + 1, reads());
    }

    @Test void successfulSaveImmediatelyRecomputesHtmlButRejectedSaveCannotPoisonTheCache() throws Exception {
        String path = path();
        var form = http.editor(path);
        form.put("content", "# First");
        assertEquals(303, http.post(path, form).statusCode());
        var first = cache.get("page:" + path, PageService.RenderedPage.class);
        assertNotNull(first);
        assertEquals("<h1>First</h1>", first.html());
        assertEquals(0, first.page().version());
        form = http.editor(path);
        form.put("content", "# Second");
        long before = renders();
        assertEquals(303, http.post(path, form).statusCode());
        var second = cache.get("page:" + path, PageService.RenderedPage.class);
        assertEquals("<h1>Second</h1>", second.html());
        assertEquals(1, second.page().version());
        assertEquals(before + 1, renders());
        form.put("content", "# Stale draft");
        assertEquals(409, http.post(path, form).statusCode());
        assertSame(second, cache.get("page:" + path, PageService.RenderedPage.class));
        assertTrue(http.get(path).body().contains("<h1>Second</h1>"));
        assertEquals(before + 1, renders());
    }

    @Test void usesProcessedPagePayloadSizeAndDoesNotCacheArbitraryMissingPages() throws Exception {
        String path = path();
        pages.savePage(path, "x".repeat(600_000), "", -1);
        // Markdown is below 1 MB, but retaining Markdown + rendered HTML exceeds the per-entry cap.
        assertNull(cache.get("page:" + path, PageService.RenderedPage.class));
        long before = renders();
        assertEquals(200, http.get(path).statusCode());
        assertEquals(200, http.get(path).statusCode());
        assertEquals(before + 2, renders());
        assertNull(cache.get("page:" + path, PageService.RenderedPage.class));
        int entries = cache.size();
        assertEquals(200, http.get(path()).statusCode());
        assertEquals(entries, cache.size());
    }

    @Test void homeIsWarmPinnedAndRecomputedOnEdit() throws Exception {
        assertNotNull(cache.get("page:/", PageService.RenderedPage.class));
        var home = pages.getPage("/");
        try {
            pages.savePage("/", "# New home", "", home.version());
            var updated = cache.get("page:/", PageService.RenderedPage.class);
            assertEquals("<h1>New home</h1>", updated.html());
            for (int i = 0; i < 4; i++) cache.put("file:pressure-" + i, "payload", 1024 * 1024);
            assertSame(updated, cache.get("page:/", PageService.RenderedPage.class));
            assertTrue(http.get("/").body().contains("<h1>New home</h1>"));
        } finally {
            pages.savePage("/", home.content(), "", pages.getPage("/").version());
        }
    }

    @Test void cachedAttachmentsAreRefreshedOnReplacementAndRemovedOnDeletion() throws Exception {
        String path = path();
        files.upload(path, "# Files", "", -1, List.of(source("old 日本")));
        var file = files.list(path).getFirst();
        double hits = metrics.counter("wiki.cache.requests", "result", "hit").count();
        assertEquals("old 日本", http.get(file.url()).body());
        assertEquals("old 日本", http.get(file.url()).body());
        assertEquals(hits + 1, metrics.counter("wiki.cache.requests", "result", "hit").count());
        files.upload(path, "# Updated files", "", pages.getPage(path).version(), List.of(source("new 日本")));
        assertEquals(file.publicId(), files.list(path).getFirst().publicId());
        assertEquals("new 日本", http.get(file.url()).body());
        assertTrue(http.get(path).body().contains("<h1>Updated files</h1>"));
        files.delete(path, file.publicId(), "", pages.getPage(path).version());
        assertEquals(404, http.get(file.url()).statusCode());
    }

    @Test void oversizedFilesAreStreamedWithoutAdmissionAndAssetsShareTheSameCache() throws Exception {
        String path = path();
        String content = "x".repeat(1024 * 1024 + 1);
        files.upload(path, "", "", -1, List.of(source(content)));
        var file = files.list(path).getFirst();
        int entries = cache.size();
        assertEquals(content, http.get(file.url()).body());
        assertEquals(entries, cache.size());
        for (String asset : List.of("style.css", "editor.js")) {
            String assetPath = asset.endsWith("css") ? "/static/css/" : "/static/js/";
            var response = http.get("/_assets/" + asset);
            assertEquals(200, response.statusCode());
            assertNotNull(cache.get("asset:" + assetPath + asset, byte[].class));
            assertEquals(response.body(), http.get("/_assets/" + asset).body());
        }
        assertEquals(cache.sizeBytes(), metrics.get("wiki.cache.bytes").gauge().value());
        assertEquals(cache.size(), metrics.get("wiki.cache.entries").gauge().value());
    }
}
