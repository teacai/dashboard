package com.teacai.wiki;

import com.teacai.wiki.model.Page;
import com.teacai.wiki.repository.PageRepository;
import com.teacai.wiki.service.PasswordService;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@Property(name = "wiki.master-password", value = "master-secret")
class MasterPasswordHttpTest {
    @Inject EmbeddedServer server;
    @Inject PageRepository repository;
    @Inject PasswordService passwords;

    @Test void masterPasswordIsRequiredForEverySaveAndOverridesExistingPagePasswords() throws Exception {
        try (var http = new HttpTestSupport(server.getURI())) {
            String path = "/master/" + UUID.randomUUID();
            var form = http.editor(path);
            form.put("content", "created");
            assertEquals(403, http.post(path, form).statusCode());
            form.put("password", "wrong-master");
            assertEquals(403, http.post(path, form).statusCode());
            assertTrue(repository.findByPath(path).isEmpty());
            form.put("password", "master-secret");
            assertEquals(303, http.post(path, form).statusCode());
            assertNull(repository.findByPath(path).orElseThrow().passwordHash());
            form = http.editor(path);
            form.put("content", "updated");
            assertEquals(403, http.post(path, form).statusCode());
            form.put("password", "master-secret");
            assertEquals(303, http.post(path, form).statusCode());

            path += "/already-protected";
            assertTrue(repository.save(new Page(path, "protected", passwords.hashPassword("page-secret"), -1), -1));
            form = http.editor(path);
            form.put("content", "Master-authorized update");
            assertEquals(403, http.post(path, form).statusCode());
            form.put("password", "page-secret");
            assertEquals(403, http.post(path, form).statusCode());
            assertEquals("protected", repository.findByPath(path).orElseThrow().content());
            form.put("password", "master-secret");
            assertEquals(303, http.post(path, form).statusCode());
            assertEquals("Master-authorized update", repository.findByPath(path).orElseThrow().content());
        }
    }
}
