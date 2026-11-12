package com.teacai.wiki;

import com.teacai.wiki.model.Page;
import com.teacai.wiki.repository.PageRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@Property(name = "wiki.master-password", value = " \t ")
class BlankMasterPasswordHttpTest {
    @Inject EmbeddedServer server;
    @Inject PageRepository repository;

    @Test void anyoneCanCreateAndUpdatePublicPagesWithWhitespaceOnlyMasterConfiguration() throws Exception {
        String path = "/blank-master/" + UUID.randomUUID();
        try (var author = new HttpTestSupport(server.getURI());
             var visitor = new HttpTestSupport(server.getURI())) {
            var form = author.editor(path);
            form.remove("password");
            form.put("content", "Created without a password");
            assertEquals(303, author.post(path, form).statusCode());

            form = visitor.editor(path);
            form.put("content", "Updated by someone else without a password");
            assertEquals(303, visitor.post(path, form).statusCode());
            var page = repository.findByPath(path).orElseThrow();
            assertEquals(form.get("content"), page.content());
            assertNull(page.passwordHash());
        }
    }

    @Test void protectedPageCanBeUpdatedByAnotherVisitorOnlyWithItsPassword() throws Exception {
        String path = "/blank-master/protected/" + UUID.randomUUID();
        try (var author = new HttpTestSupport(server.getURI());
             var visitor = new HttpTestSupport(server.getURI())) {
            var form = author.editor(path);
            form.put("content", "Protected");
            form.put("password", "page-secret");
            assertEquals(303, author.post(path, form).statusCode());

            form = visitor.editor(path);
            form.put("content", "Visitor update");
            assertEquals(403, visitor.post(path, form).statusCode());
            form.put("password", "wrong");
            assertEquals(403, visitor.post(path, form).statusCode());
            assertEquals("Protected", repository.findByPath(path).orElseThrow().content());
            form.put("password", "page-secret");
            assertEquals(303, visitor.post(path, form).statusCode());
            assertEquals("Visitor update", repository.findByPath(path).orElseThrow().content());
        }
    }

    @Test void emptyStoredPasswordHashesArePublicAndCanLaterBeProtected() throws Exception {
        try (var visitor = new HttpTestSupport(server.getURI())) {
            for (String emptyHash : new String[]{"", " \t "}) {
                String path = "/blank-master/empty-hash/" + UUID.randomUUID();
                assertTrue(repository.save(new Page(path, "Existing public page", emptyHash, -1), -1));
                var editor = visitor.get(path + "/editor");
                assertEquals("Set password (optional)", Jsoup.parse(editor.body())
                        .selectFirst("input[name=password]").attr("placeholder"));
                var form = visitor.editor(path);
                form.remove("password");
                form.put("content", "Public update");
                assertEquals(303, visitor.post(path, form).statusCode());
                assertNull(repository.findByPath(path).orElseThrow().passwordHash());

                form = visitor.editor(path);
                form.put("password", "new-page-secret");
                assertEquals(303, visitor.post(path, form).statusCode());
                assertTrue(repository.findByPath(path).orElseThrow().isProtected());
                form = visitor.editor(path);
                assertEquals(403, visitor.post(path, form).statusCode());
            }
        }
    }
}
