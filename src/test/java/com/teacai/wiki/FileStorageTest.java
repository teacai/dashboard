package com.teacai.wiki;

import com.teacai.wiki.repository.PageRepository;
import com.teacai.wiki.repository.FileRepository;
import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.PageService;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {
    @TempDir Path directory;

    @Test void attachmentsAndStableLinksSurviveRestartWithTheirOwningPage() throws Exception {
        var config = Map.<String, Object>of("datasources.default.url", "jdbc:h2:file:" + directory.resolve("attachments"),
                "wiki.master-password", "", "wiki.files-directory", directory.resolve("files").toString());
        UUID publicId;
        try (var context = ApplicationContext.run(config, "test")) {
            context.getBean(FileService.class).upload("/persisted/nested", "Durable draft", "password", -1,
                    List.of(new AttachmentStorage.Source("folder/file.txt", 3, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}))));
            publicId = context.getBean(FileRepository.class).list("/persisted/nested").getFirst().publicId();
        }
        try (var context = ApplicationContext.run(config, "test")) {
            var file = context.getBean(FileRepository.class).list("/persisted/nested").getFirst();
            assertEquals(publicId, file.publicId());
            assertEquals("folder/file.txt", file.relativePath());
            try (var stream = context.getBean(AttachmentStorage.class).open(file)) {
                assertArrayEquals(new byte[]{1, 2, 3}, stream.readAllBytes());
            }
            var page = context.getBean(PageService.class).getPage(file.pagePath());
            assertTrue(page.content().contains(file.url()));
            assertTrue(page.isProtected());
            context.getBean(FileService.class).delete(file.pagePath(), file.publicId(), "password", page.version());
            assertTrue(context.getBean(FileRepository.class).list(file.pagePath()).isEmpty());
        }
    }

    @Test void migrationsAndPagesSurviveClosingAndReopeningFileDatabase() {
        var config = Map.<String, Object>of("datasources.default.url", "jdbc:h2:file:" + directory.resolve("wiki"),
                "wiki.master-password", "");
        try (var context = ApplicationContext.run(config, "test")) {
            context.getBean(PageService.class).savePage("/persisted/nested", "# Durable", "password", -1);
        }
        try (var context = ApplicationContext.run(config, "test")) {
            var page = context.getBean(PageRepository.class).findByPath("/persisted/nested").orElseThrow();
            assertEquals("# Durable", page.content());
            assertEquals(0, page.version());
            assertTrue(page.isProtected());
            context.getBean(PageService.class).savePage(page.path(), "updated", "password", page.version());
            assertEquals(1, context.getBean(PageRepository.class).findByPath(page.path()).orElseThrow().version());
        }
    }
}
