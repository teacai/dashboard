package com.teacai.wiki;

import com.teacai.wiki.repository.PageRepository;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
class EmojiHttpTest {
    @Inject EmbeddedServer server;
    @Inject PageRepository repository;

    @Test void rendersUtf8EmojiWithoutChangingStoredMarkdownOrTheEditor() throws Exception {
        String path = "/emoji/" + UUID.randomUUID();
        String markdown = "# Hello :smile:\n\n**:heart:** :+1: :us: :unknown: :octocat:\n\n`:rocket:`";
        try (var http = new HttpTestSupport(server.getURI())) {
            var form = http.editor(path);
            form.put("content", markdown);
            assertEquals(303, http.post(path, form).statusCode());

            var response = http.get(path);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<h1>Hello 😄</h1>"));
            assertTrue(response.body().contains("<strong>❤️</strong> 👍 🇺🇸 :unknown: :octocat:"));
            assertEquals(":rocket:", Jsoup.parse(response.body()).selectFirst("code").text());
            assertEquals(markdown, repository.findByPath(path).orElseThrow().content());
            assertEquals(markdown, http.editor(path).get("content"));
            assertEquals(markdown, http.get(path + ".md").body());
        }
    }
}
