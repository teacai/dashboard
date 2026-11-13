package com.teacai.wiki;

import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.ContentCache;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.PageService;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
class BrowserCacheHttpTest {
    @Inject EmbeddedServer server;
    @Inject FileService files;
    @Inject PageService pages;
    @Inject ContentCache cache;

    private HttpResponse<byte[]> get(HttpClient client, String path, String etag) throws Exception {
        var request = HttpRequest.newBuilder(server.getURI().resolve(path)).GET();
        if (etag != null) request.header("If-None-Match", etag);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String validator(HttpResponse<?> response) {
        assertEquals("public, no-cache", response.headers().firstValue("cache-control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
        return response.headers().firstValue("etag").orElseThrow();
    }

    private AttachmentStorage.Source source(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new AttachmentStorage.Source("file.txt", bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    @Test void assetsHaveStableValidatorsAndEmpty304ResponsesWithSecurityHeaders() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : List.of("/_assets/style.css", "/_assets/editor.js", "/favicon.ico")) {
                var original = get(client, path, null);
                assertEquals(200, original.statusCode());
                String etag = validator(original);
                for (String condition : List.of(etag, "\"older\", W/" + etag, "*")) {
                    var unchanged = get(client, path, condition);
                    assertEquals(304, unchanged.statusCode());
                    assertEquals(0, unchanged.body().length);
                    assertEquals(etag, validator(unchanged));
                }
                var stale = get(client, path, "\"stale\"");
                assertEquals(200, stale.statusCode());
                assertArrayEquals(original.body(), stale.body());
                cache.clear();
                assertEquals(304, get(client, path, etag).statusCode());
            }
        } finally { pages.resetCache(); }
    }

    @Test void uploadsRevalidateAcrossCacheEvictionSameSizeReplacementAndDeletion() throws Exception {
        String path = "/browser-cache/" + UUID.randomUUID();
        files.upload(path, "", "", -1, List.of(source("old")));
        var file = files.list(path).getFirst();
        try (var client = HttpClient.newHttpClient()) {
            var original = get(client, file.url(), null);
            assertEquals(200, original.statusCode());
            assertEquals("old", new String(original.body(), StandardCharsets.UTF_8));
            String etag = validator(original);
            var unchanged = get(client, file.url(), etag);
            assertEquals(304, unchanged.statusCode());
            assertEquals(0, unchanged.body().length);
            assertEquals(etag, validator(unchanged));
            cache.clear();
            assertEquals(304, get(client, file.url(), etag).statusCode());

            files.upload(path, "", "", pages.getPage(path).version(), List.of(source("new")));
            assertEquals(file.publicId(), files.list(path).getFirst().publicId());
            var replaced = get(client, file.url(), etag);
            assertEquals(200, replaced.statusCode());
            assertEquals("new", new String(replaced.body(), StandardCharsets.UTF_8));
            String updatedEtag = validator(replaced);
            assertNotEquals(etag, updatedEtag);
            assertTrue(replaced.headers().firstValue("content-disposition").orElseThrow().startsWith("attachment"));
            assertTrue(replaced.headers().firstValue("content-security-policy").orElseThrow().contains("sandbox"));
            assertEquals(304, get(client, file.url(), updatedEtag).statusCode());

            files.delete(path, file.publicId(), "", pages.getPage(path).version());
            var deleted = get(client, file.url(), "*");
            assertEquals(404, deleted.statusCode());
            assertEquals("no-store", deleted.headers().firstValue("cache-control").orElseThrow());
        } finally { pages.resetCache(); }
    }

    @Test void streamedLargeUploadsAlsoRevalidateWithoutEnteringThePayloadCache() throws Exception {
        String path = "/browser-cache/" + UUID.randomUUID();
        files.upload(path, "", "", -1, List.of(source("x".repeat(1024 * 1024 + 1))));
        var file = files.list(path).getFirst();
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, file.url(), null);
            assertEquals(200, response.statusCode());
            assertEquals(1024 * 1024 + 1, response.body().length);
            String etag = validator(response);
            assertEquals(304, get(client, file.url(), etag).statusCode());
            assertNull(cache.get("file:" + file.publicId(), Object.class));
        }
    }

    @Test void dynamicPagesAndInvalidDownloadsRemainNoStoreEvenWithConditionalHeaders() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : List.of("/", "/editor", "/.md", "/_files/not-a-uuid")) {
                var response = get(client, path, "*");
                assertNotEquals(304, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
            }
        }
    }
}
