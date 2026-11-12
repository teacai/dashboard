package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.repository.FileRepository;
import com.teacai.wiki.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RestoreTest {
    @TempDir Path directory;

    private Map<String, Object> config() throws IOException {
        Path root = Files.createDirectories(directory.resolve("data"));
        return Map.of("datasources.default.url", "jdbc:h2:file:" + root.resolve("db/wiki"),
                "datasources.default.connection-timeout", 1000,
                "wiki.data-directory", root.toString(), "wiki.files-directory", root.resolve("files").toString(),
                "wiki.master-password", "", "wiki.admin-username", "operator", "wiki.admin-password", "restore-pass",
                "micronaut.server.port", -1);
    }

    private String authorization(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    private String authorization() { return authorization("operator", "restore-pass"); }

    private HttpResponse<byte[]> request(HttpClient client, URI base, byte[] body, String auth, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve("/_backup"));
        if (auth != null) builder.header("Authorization", auth);
        if (body == null) builder.GET();
        else builder.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void upload(ApplicationContext context, String text) throws IOException {
        var pages = context.getBean(PageService.class);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        context.getBean(FileService.class).upload("/restored", text, "", pages.getPage("/restored").version(),
                List.of(new AttachmentStorage.Source("folder/file.txt", bytes.length, () -> new ByteArrayInputStream(bytes))));
    }

    private byte[] backup(ApplicationContext context) throws Exception {
        try (var archive = context.getBean(BackupService.class).create()) { return archive.stream().readAllBytes(); }
    }

    @Test void basicAuthenticationProtectsBothMethodsAndUsesConfiguredCredentials() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = HttpClient.newHttpClient()) {
            for (String auth : new String[]{null, "Basic malformed", "Bearer token", authorization("admin", "admin112"), authorization("operator", "wrong")}) {
                for (byte[] body : new byte[][]{null, "invalid archive".getBytes(StandardCharsets.UTF_8)}) {
                    var response = request(client, server.getURI(), body, auth, "application/zip");
                    assertEquals(401, response.statusCode());
                    assertTrue(response.headers().firstValue("www-authenticate").orElseThrow().startsWith("Basic "));
                }
            }
            assertEquals(200, request(client, server.getURI(), null, authorization(), null).statusCode());
            assertEquals(400, request(client, server.getURI(), new byte[]{1, 2, 3}, authorization(), "application/zip").statusCode());
            var crossSite = client.send(HttpRequest.newBuilder(server.getURI().resolve("/_backup"))
                    .header("Authorization", authorization()).header("Origin", "https://evil.example")
                    .header("Content-Type", "application/zip").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1})).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(403, crossSite.statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(server.getURI()).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void rawZipAndMultipartRestoreReplaceAllDataAndApplicationKeepsWorking() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = HttpClient.newHttpClient()) {
            var context = server.getApplicationContext();
            Path root = directory.resolve("data");
            upload(context, "original");
            Files.writeString(root.resolve("unmanaged.txt"), "original unmanaged");
            byte[] archive = request(client, server.getURI(), null, authorization(), null).body();
            var original = context.getBean(FileRepository.class).list("/restored").getFirst();
            upload(context, "newer data");
            Files.writeString(root.resolve("unmanaged.txt"), "newer unmanaged");
            Files.writeString(root.resolve("created-after-backup.txt"), "remove me");
            var response = request(client, server.getURI(), archive, authorization(), "application/zip");
            assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            assertTrue(context.getBean(PageService.class).getPage("/restored").content().startsWith("original"));
            assertEquals(original, context.getBean(FileRepository.class).list("/restored").getFirst());
            assertEquals("original", Files.readString(root.resolve("files/restored/folder/file.txt")));
            assertEquals("original unmanaged", Files.readString(root.resolve("unmanaged.txt")));
            assertFalse(Files.exists(root.resolve("created-after-backup.txt")));
            var page = client.send(HttpRequest.newBuilder(server.getURI().resolve("/restored")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("original"));
            upload(context, "after restore");
            String boundary = "restore-boundary";
            var multipart = new ByteArrayOutputStream();
            multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"backup.zip\"\r\n"
                    + "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            multipart.write(archive);
            multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var restored = request(client, server.getURI(), multipart.toByteArray(), authorization(), "multipart/form-data; boundary=" + boundary);
            assertEquals(200, restored.statusCode(), new String(restored.body(), StandardCharsets.UTF_8));
            assertTrue(context.getBean(PageService.class).getPage("/restored").content().startsWith("original"));
            assertEquals(200, request(client, server.getURI(), null, authorization(), null).statusCode());
            try (var paths = Files.list(root)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".wiki-"))); }
            assertEquals(2, context.getBean(MeterRegistry.class).get("wiki.restores").tag("result", "success").counter().count());
        }
    }

    @Test void invalidDatabaseMissingFilesAndInvalidSchemaLeaveCurrentDataUntouched() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = HttpClient.newHttpClient()) {
            var context = server.getApplicationContext();
            upload(context, "before backup");
            var entries = unzip(backup(context));
            upload(context, "keep current");
            var missingDatabase = new LinkedHashMap<>(entries);
            missingDatabase.remove("db/wiki.mv.db");
            var corruptDatabase = new LinkedHashMap<>(entries);
            corruptDatabase.put("db/wiki.mv.db", new byte[]{1, 2, 3});
            var missingAttachment = new LinkedHashMap<>(entries);
            missingAttachment.remove("files/restored/folder/file.txt");
            var wrongSize = new LinkedHashMap<>(entries);
            wrongSize.put("files/restored/folder/file.txt", new byte[]{1});
            var wrongSchema = new LinkedHashMap<>(entries);
            Path changed = Files.createDirectories(directory.resolve("wrong-schema")).resolve("wiki.mv.db");
            Files.write(changed, entries.get("db/wiki.mv.db"));
            try (var connection = DriverManager.getConnection("jdbc:h2:file:" + changed.getParent().resolve("wiki"), "sa", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE \"flyway_schema_history\" SET \"checksum\" = 0 WHERE \"version\" = '3'");
            }
            wrongSchema.put("db/wiki.mv.db", Files.readAllBytes(changed));
            for (var invalid : List.of(missingDatabase, corruptDatabase, missingAttachment, wrongSize, wrongSchema)) {
                var response = request(client, server.getURI(), zip(invalid), authorization(), "application/zip");
                assertEquals(400, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
                assertTrue(context.getBean(PageService.class).getPage("/restored").content().startsWith("keep current"));
                assertEquals("keep current", Files.readString(directory.resolve("data/files/restored/folder/file.txt")));
            }
        }
    }

    @Test void failureReopeningInstalledDatabaseRollsBackBothDatabaseAndFiles() throws Exception {
        try (var context = ApplicationContext.run(config(), "test")) {
            upload(context, "backup contents");
            byte[] archive = backup(context);
            upload(context, "current contents");
            Path root = directory.resolve("data").toRealPath();
            Files.writeString(root.resolve("current-only.txt"), "must survive rollback");
            var failedOnce = new AtomicBoolean();
            var validator = new RestoreValidator() {
                @Override public void validate(Connection connection, Path attachments, List<Migration> migrations) throws SQLException, IOException {
                    super.validate(connection, attachments, migrations);
                    if (attachments.equals(root.resolve("files")) && failedOnce.compareAndSet(false, true)) {
                        throw new IOException("Simulated failure reading the installed database");
                    }
                }
            };
            var service = new RestoreService(context.getBean(DataSource.class), context.getBean(WikiProperties.class),
                    context.getBean(AttachmentStorage.class), context.getBean(PageService.class), context.getBean(DataMaintenance.class),
                    context.getBean(RestoreArchive.class), validator, context.getBean(MeterRegistry.class));
            assertThrows(IOException.class, () -> service.restore(new ByteArrayInputStream(archive)));
            assertTrue(failedOnce.get());
            assertTrue(context.getBean(PageService.class).getPage("/restored").content().startsWith("current contents"));
            assertEquals("current contents", Files.readString(root.resolve("files/restored/folder/file.txt")));
            assertEquals("must survive rollback", Files.readString(root.resolve("current-only.txt")));
            assertFalse(context.getBean(DataMaintenance.class).isUnavailable());
            assertEquals(1, context.getBean(MeterRegistry.class).get("wiki.restores").tag("result", "rollback").counter().count());
            upload(context, "still writable");
        }
    }

    @Test void failedRollbackValidationPreservesRecoveryDirectoriesAndFailsClosed() throws Exception {
        try (var context = ApplicationContext.run(config(), "test")) {
            upload(context, "backup contents");
            byte[] archive = backup(context);
            upload(context, "current contents");
            Path root = directory.resolve("data").toRealPath();
            var validator = new RestoreValidator() {
                @Override public void validate(Connection connection, Path attachments, List<Migration> migrations) throws SQLException, IOException {
                    super.validate(connection, attachments, migrations);
                    if (attachments.equals(root.resolve("files"))) throw new IOException("Simulated installed/rollback database failure");
                }
            };
            var maintenance = context.getBean(DataMaintenance.class);
            var service = new RestoreService(context.getBean(DataSource.class), context.getBean(WikiProperties.class),
                    context.getBean(AttachmentStorage.class), context.getBean(PageService.class), maintenance,
                    context.getBean(RestoreArchive.class), validator, context.getBean(MeterRegistry.class));
            var error = assertThrows(IOException.class, () -> service.restore(new ByteArrayInputStream(archive)));
            assertEquals(1, error.getSuppressed().length);
            assertTrue(maintenance.isUnavailable());
            assertEquals(0, maintenance.admitRequest());
            try (var paths = Files.list(root)) {
                var names = paths.map(p -> p.getFileName().toString()).toList();
                assertTrue(names.stream().anyMatch(n -> n.startsWith(".wiki-previous-")));
                assertTrue(names.stream().anyMatch(n -> n.startsWith(".wiki-restore-")));
            }
        }
    }

    static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var result = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(result)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return result.toByteArray();
    }
}
