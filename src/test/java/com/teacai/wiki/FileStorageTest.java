package com.teacai.wiki;

import com.teacai.wiki.repository.PageRepository;
import com.teacai.wiki.service.PageService;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {
    @TempDir Path directory;

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
