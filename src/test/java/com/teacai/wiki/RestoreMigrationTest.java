package com.teacai.wiki;

import com.teacai.wiki.repository.FileRepository;
import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.PageService;
import com.teacai.wiki.service.PasswordService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestoreMigrationTest {
    @TempDir Path directory;

    private record OldDatabase(Path database, String url, String passwordHash, boolean hasFiles) {}

    private Map<String, Object> config(Path root) {
        return Map.of("datasources.default.url", "jdbc:h2:file:" + root.resolve("db/wiki"),
                "wiki.data-directory", root.toString(), "wiki.files-directory", root.resolve("files").toString(),
                "wiki.master-password", "", "wiki.admin-username", "operator", "wiki.admin-password", "restore-pass",
                "micronaut.server.port", -1);
    }

    private OldDatabase database(int version, String futureVersion) throws Exception {
        Path source = Files.createTempDirectory(directory, "source-");
        String url = "jdbc:h2:file:" + source.resolve("db/wiki") + ";DB_CLOSE_ON_EXIT=FALSE";
        var config = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target(Integer.toString(version));
        if (futureVersion != null) {
            Path scripts = Files.createDirectory(source.resolve("future-migrations"));
            Files.writeString(scripts.resolve("V" + futureVersion + "__future_application.sql"),
                    "CREATE TABLE future_application_data (id BIGINT PRIMARY KEY);");
            config.locations("classpath:db/migration", "filesystem:" + scripts).target("latest");
        }
        config.load().migrate();
        String hash = new PasswordService().hashPassword("legacy-password");
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement("INSERT INTO wiki_pages(path, content, password_hash) VALUES (?, ?, ?)")) {
            statement.setString(1, version == 1 ? "legacy/nested" : "/legacy/nested");
            statement.setString(2, "Original old content");
            statement.setString(3, hash);
            statement.executeUpdate();
            statement.setString(1, "/empty");
            statement.setString(2, version == 1 ? null : "");
            statement.setString(3, null);
            statement.executeUpdate();
        }
        return new OldDatabase(source.resolve("db/wiki.mv.db"), url, hash, version >= 3);
    }

    private byte[] archive(OldDatabase source) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("db/wiki.mv.db", Files.readAllBytes(source.database()));
        entries.put("notes.txt", "old unmanaged file".getBytes(StandardCharsets.UTF_8));
        if (source.hasFiles()) entries.put("files/", new byte[0]);
        return RestoreTest.zip(entries);
    }

    private HttpResponse<String> restore(HttpClient client, EmbeddedServer server, byte[] zip) throws Exception {
        String auth = Base64.getEncoder().encodeToString("operator:restore-pass".getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(server.getURI().resolve("/_backup"))
                .header("Authorization", "Basic " + auth).header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(zip)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void seedLive(ApplicationContext context, Path root) throws Exception {
        context.getBean(FileService.class).upload("/current", "Keep current data", "", -1,
                List.of(new AttachmentStorage.Source("live.txt", 3, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}))));
        Files.writeString(root.resolve("current-only.txt"), "keep current file");
    }

    private void assertCurrentUnchanged(ApplicationContext context, Path root) throws Exception {
        assertTrue(context.getBean(PageService.class).getPage("/current").content().startsWith("Keep current data"));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("files/current/live.txt")));
        assertEquals("keep current file", Files.readString(root.resolve("current-only.txt")));
        assertEquals(-1, context.getBean(PageService.class).getPage("/legacy/nested").version());
        try (var paths = Files.list(root)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".wiki-")));
        }
    }

    @Test void restoresV1AndV2BackupsByMigratingOnlyTheStagedCopyAndSurvivesRestart() throws Exception {
        for (int version : List.of(1, 2)) {
            var source = database(version, null);
            byte[] originalDatabase = Files.readAllBytes(source.database());
            Path root = directory.resolve("live-v" + version);
            var config = config(root);
            try (var server = ApplicationContext.run(EmbeddedServer.class, config, "test"); var client = HttpClient.newHttpClient()) {
                var context = server.getApplicationContext();
                seedLive(context, root);
                var response = restore(client, server, archive(source));
                assertEquals(200, response.statusCode(), response.body());
                var pages = context.getBean(PageService.class);
                var page = pages.getPage("/legacy/nested");
                assertEquals("Original old content", page.content());
                assertEquals(0, page.version());
                assertEquals(source.passwordHash(), page.passwordHash());
                assertEquals("", pages.getPage("/empty").content());
                assertEquals(0, pages.getPage("/empty").version());
                assertEquals(-1, pages.getPage("/current").version());
                assertTrue(context.getBean(FileRepository.class).list("/legacy/nested").isEmpty());
                assertTrue(Files.isDirectory(root.resolve("files"))); // Not present in pre-attachment backups.
                assertFalse(Files.exists(root.resolve("files/current/live.txt")));
                assertFalse(Files.exists(root.resolve("current-only.txt")));
                assertEquals("old unmanaged file", Files.readString(root.resolve("notes.txt")));
                assertEquals(3 - version, context.getBean(MeterRegistry.class).get("wiki.restore.migrations").counter().count());
                Flyway.configure().dataSource(context.getBean(DataSource.class)).load().validate();
                // The restored password hash still authenticates writes after migration.
                pages.savePage(page.path(), "Updated after upgrade", "legacy-password", page.version());
                assertArrayEquals(originalDatabase, Files.readAllBytes(source.database()));
            }
            try (var context = ApplicationContext.run(config, "test")) {
                assertEquals("Updated after upgrade", context.getBean(PageService.class).getPage("/legacy/nested").content());
                assertEquals(1, context.getBean(PageService.class).getPage("/legacy/nested").version());
            }
        }
    }

    @Test void newerApplicationSchemasAreRejectedWithAnExplicitErrorAndNoLiveChanges() throws Exception {
        Path root = directory.resolve("live");
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(root), "test"); var client = HttpClient.newHttpClient()) {
            var context = server.getApplicationContext();
            seedLive(context, root);
            for (String futureVersion : List.of("4", "10", "3.1")) {
                var source = database(3, futureVersion);
                byte[] originalDatabase = Files.readAllBytes(source.database());
                var response = restore(client, server, archive(source));
                assertEquals(400, response.statusCode(), response.body());
                assertTrue(response.body().contains("newer application database version"), response.body());
                assertTrue(response.body().contains("Upgrade the application"));
                assertCurrentUnchanged(context, root);
                assertArrayEquals(originalDatabase, Files.readAllBytes(source.database()));
            }
            assertNull(context.getBean(MeterRegistry.class).find("wiki.restore.migrations").counter());
        }
    }

    @Test void aFailingUpgradeDoesNotTouchTheLiveDatabaseFilesOrSourceBackup() throws Exception {
        var source = database(1, null);
        // An inconsistent old schema: V2 will alter content and then fail when adding this existing column.
        try (var connection = DriverManager.getConnection(source.url(), "sa", ""); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE wiki_pages ADD COLUMN version BIGINT DEFAULT 0 NOT NULL");
        }
        byte[] originalDatabase = Files.readAllBytes(source.database());
        Path root = directory.resolve("live");
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(root), "test"); var client = HttpClient.newHttpClient()) {
            var context = server.getApplicationContext();
            seedLive(context, root);
            var response = restore(client, server, archive(source));
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("schema migration"));
            assertCurrentUnchanged(context, root);
            assertArrayEquals(originalDatabase, Files.readAllBytes(source.database()));
        }
    }

    @Test void anOlderBackupWithAlteredMigrationChecksumsIsRejectedBeforeUpgrade() throws Exception {
        var source = database(1, null);
        try (var connection = DriverManager.getConnection(source.url(), "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE \"flyway_schema_history\" SET \"checksum\" = 0 WHERE \"version\" = '1'");
        }
        Path root = directory.resolve("live");
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(root), "test"); var client = HttpClient.newHttpClient()) {
            var context = server.getApplicationContext();
            seedLive(context, root);
            var response = restore(client, server, archive(source));
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("migration history is incompatible"));
            assertCurrentUnchanged(context, root);
        }
    }
}
