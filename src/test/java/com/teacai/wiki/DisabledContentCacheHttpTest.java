package com.teacai.wiki;

import com.teacai.wiki.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DisabledContentCacheHttpTest {
    @TempDir Path directory;

    @Test void eitherZeroDisablesHomePageAndAssetCachingWithoutChangingResponses() throws Exception {
        for (String setting : new String[]{"wiki.cache-max-file-size-mb", "wiki.cache-max-size-mb"}) {
            var config = Map.<String, Object>of(setting, 0,
                    "datasources.default.url", "jdbc:h2:mem:disabled_" + UUID.randomUUID(),
                    "wiki.files-directory", directory.resolve(UUID.randomUUID().toString()).toString(),
                    "wiki.master-password", "", "micronaut.server.port", -1);
            try (var server = ApplicationContext.run(EmbeddedServer.class, config, "test");
                 var http = new HttpTestSupport(server.getURI())) {
                var context = server.getApplicationContext();
                var pages = context.getBean(PageService.class);
                var metrics = context.getBean(MeterRegistry.class);
                var cache = context.getBean(ContentCache.class);
                assertFalse(cache.isEnabled());
                pages.savePage("/", "# Uncached home", "", -1);
                long beforeRenders = metrics.timer("wiki.markdown.render").count();
                long beforeReads = metrics.timer("wiki.database.read").count();
                for (int i = 0; i < 2; i++) assertTrue(http.get("/").body().contains("<h1>Uncached home</h1>"));
                assertEquals(beforeRenders + 2, metrics.timer("wiki.markdown.render").count());
                assertEquals(beforeReads + 2, metrics.timer("wiki.database.read").count());
                assertEquals("# Uncached home", http.get("/.md").body());
                assertEquals(200, http.get("/_assets/style.css").statusCode());
                assertEquals(0, cache.size());
                assertEquals(0, cache.sizeBytes());
            }
        }
    }
}
