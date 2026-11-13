package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.repository.PageRepository;
import com.teacai.wiki.service.ContentCache;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
class FaviconHttpTest {
    @Inject EmbeddedServer server;
    @Inject ContentCache cache;
    @Inject PageRepository pages;
    @Inject WikiProperties properties;

    @Test void servesTheIconAtTheBrowserDefaultUrlAndCachesItAsAnAsset() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(server.getURI().resolve("/favicon.ico"))
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("image/x-icon"));
            try (var input = getClass().getResourceAsStream("/static/favicon.ico")) {
                assertNotNull(input);
                assertArrayEquals(input.readAllBytes(), response.body());
            }
            assertArrayEquals(response.body(), cache.get("asset:/static/favicon.ico", byte[].class));
            assertArrayEquals(response.body(), client.send(request, HttpResponse.BodyHandlers.ofByteArray()).body());
            assertTrue(pages.findByPath("/favicon.ico").isEmpty());
        }
    }

    @Test void pageEditorAndAdminTemplatesDeclareTheIconAndItsUrlCannotBecomeAnEditablePage() throws Exception {
        try (var http = new HttpTestSupport(server.getURI()); var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/", "/editor"}) {
                assertEquals("/favicon.ico", Jsoup.parse(http.get(path).body()).selectFirst("link[rel=icon]").attr("href"));
            }
            String credentials = properties.getAdminUsername() + ":" + properties.getAdminPassword();
            var admin = client.send(HttpRequest.newBuilder(server.getURI().resolve("/_admin"))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, admin.statusCode());
            assertEquals("/favicon.ico", Jsoup.parse(admin.body()).selectFirst("link[rel=icon]").attr("href"));
            assertEquals(404, http.get("/favicon.ico/editor").statusCode());
            assertEquals(404, http.get("/favicon.ico.md").statusCode());
        }
    }
}
