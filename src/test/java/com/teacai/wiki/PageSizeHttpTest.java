package com.teacai.wiki;

import com.teacai.wiki.repository.PageRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@Property(name = "wiki.max-page-size", value = "1024")
class PageSizeHttpTest {
    @Inject EmbeddedServer server;
    @Inject PageRepository repository;

    @Test void enforcesDecodedUtf8SizeNotEncodedFormOrCharacterCount() throws Exception {
        try (var http = new HttpTestSupport(server.getURI())) {
            String path = "/size/" + UUID.randomUUID();
            var form = http.editor(path);
            // URL-encoded body is > 3 KB, but decoded content is exactly 1024 bytes.
            form.put("content", "é".repeat(512));
            assertEquals(303, http.post(path, form).statusCode());
            form = http.editor(path);
            form.put("content", "é".repeat(513));
            assertEquals(413, http.post(path, form).statusCode());
            assertEquals("é".repeat(512), repository.findByPath(path).orElseThrow().content());
            form.put("content", "x".repeat(1025));
            assertEquals(413, http.post(path, form).statusCode());
        }
    }
}
