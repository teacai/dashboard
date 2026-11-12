package com.teacai.wiki;

import com.teacai.wiki.repository.FileRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@Property(name = "wiki.master-password", value = "master-upload-secret")
class MasterPasswordAttachmentHttpTest {
    @Inject EmbeddedServer server;
    @Inject FileRepository files;

    @Test void masterPasswordIsRequiredForUploadsAndDeletion() throws Exception {
        try (var http = new HttpTestSupport(server.getURI())) {
            String path = "/master-files/" + UUID.randomUUID();
            var form = http.editor(path);
            form.put("page", path);
            form.put("manifest", "[\"file.txt\"]");
            var parts = List.of(new HttpTestSupport.UploadPart("file0", "file.txt", new byte[]{1, 2, 3}));
            assertEquals(403, http.upload(form, parts).statusCode());
            assertTrue(files.list(path).isEmpty());
            form.put("password", "master-upload-secret");
            assertEquals(303, http.upload(form, parts).statusCode());
            var file = files.list(path).getFirst();
            var deletion = http.editor(path);
            deletion.put("page", path);
            deletion.put("fileId", file.publicId().toString());
            assertEquals(403, http.post("/_files/delete", deletion).statusCode());
            deletion.put("password", "master-upload-secret");
            assertEquals(200, http.post("/_files/delete", deletion).statusCode());
            assertTrue(files.list(path).isEmpty());
        }
    }
}
