package com.teacai.wiki;

import com.teacai.wiki.repository.FileRepository;
import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.BackupService;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.PageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class BackupTest {
    @TempDir Path directory;

    private Map<String, Object> config(Path root) throws IOException {
        Files.createDirectories(root.resolve("files"));
        return new HashMap<>(Map.of("datasources.default.url", "jdbc:h2:file:" + root.resolve("wiki"),
                "wiki.data-directory", root.toString(), "wiki.files-directory", root.resolve("files").toString(),
                "wiki.master-password", "master-secret", "micronaut.server.port", -1));
    }

    @Test void authenticatedHttpDownloadIncludesAllDataAndRestoresLiveDatabaseAndAttachments() throws Exception {
        Path root = directory.resolve("data");
        var config = config(root);
        Files.createDirectories(root.resolve("empty/nested"));
        Files.createDirectories(root.resolve("backups"));
        Files.writeString(root.resolve("backups/previous.zip"), "previous archive");
        Files.writeString(root.resolve("notes.txt"), "unmanaged data");
        Map<String, byte[]> entries;
        try (var server = ApplicationContext.run(EmbeddedServer.class, config, "test");
             var client = HttpClient.newHttpClient()) {
            var context = server.getApplicationContext();
            context.getBean(FileService.class).upload("/nested", "Saved draft", "master-secret", -1,
                    List.of(new AttachmentStorage.Source("folder/日本語.txt", 5,
                            () -> new ByteArrayInputStream("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"));
            var response = client.send(HttpRequest.newBuilder(server.getURI().resolve("/_backup"))
                    .header("Authorization", basic())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals("application/zip", response.headers().firstValue("content-type").orElseThrow());
            assertTrue(response.headers().firstValue("content-disposition").orElseThrow()
                    .contains("backup_" + date + ".zip"));
            assertTrue(response.headers().firstValue("content-disposition").orElseThrow().startsWith("attachment;"));
            assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
            assertTrue(response.headers().firstValue("content-security-policy").orElseThrow().contains("sandbox"));
            assertEquals(response.body().length, Long.parseLong(response.headers().firstValue("content-length").orElseThrow()));
            entries = unzip(response.body());
            assertTrue(entries.containsKey("wiki.mv.db"));
            assertTrue(entries.containsKey("empty/nested/"));
            assertEquals("hello", new String(entries.get("files/nested/folder/日本語.txt"), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("unmanaged data", new String(entries.get("notes.txt"), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("previous archive", new String(entries.get("backups/previous.zip"), java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(entries.containsKey("wiki.lock.db"));
            assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith("wiki-backup-") || name.startsWith("wiki-database-")));
            assertEquals(1, context.getBean(MeterRegistry.class).get("wiki.backups").tag("result", "success").counter().count());
            assertEquals(1, context.getBean(MeterRegistry.class).get("wiki.backup.duration").timer().count());
            // The app remains usable after the live backup, and newer changes do not enter this archive.
            var pages = context.getBean(PageService.class);
            pages.savePage("/nested", "after backup", "master-secret", pages.getPage("/nested").version());
        }
        Path restored = directory.resolve("restored");
        for (var entry : entries.entrySet()) {
            Path target = restored.resolve(entry.getKey());
            if (entry.getKey().endsWith("/")) Files.createDirectories(target);
            else {
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
        }
        try (var context = ApplicationContext.run(config(restored), "test")) {
            var page = context.getBean(PageService.class).getPage("/nested");
            assertTrue(page.content().startsWith("Saved draft"));
            assertFalse(page.content().contains("after backup"));
            var attachment = context.getBean(FileRepository.class).list("/nested").getFirst();
            assertTrue(page.content().contains(attachment.url()));
            try (var input = context.getBean(AttachmentStorage.class).open(attachment)) {
                assertEquals("hello", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    @Test void freshInstallationCanDownloadBeforeAnyPageOrAttachmentRequest() throws Exception {
        Path root = directory.resolve("fresh");
        var config = config(root);
        Files.delete(root.resolve("files"));
        try (var server = ApplicationContext.run(EmbeddedServer.class, config, "test");
             var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(server.getURI().resolve("/_backup")).header("Authorization", basic()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            var entries = unzip(response.body());
            assertTrue(entries.containsKey("wiki.mv.db"));
            assertTrue(entries.containsKey("files/"));
        }
    }

    @Test void rejectsInMemoryDatabaseInsteadOfReturningIncompleteBackup() throws Exception {
        var config = config(directory.resolve("data"));
        config.put("datasources.default.url", "jdbc:h2:mem:backup_test");
        try (var context = ApplicationContext.run(config, "test")) {
            assertThrows(IOException.class, () -> context.getBean(BackupService.class).create());
        }
    }

    @Test void rejectsSymlinksRatherThanExposingFilesOutsideDataAndCanRetryAfterFailure() throws Exception {
        Path root = directory.resolve("data");
        var config = config(root);
        Path secret = Files.writeString(directory.resolve("secret.txt"), "outside data");
        Path link = Files.createSymbolicLink(root.resolve("link.txt"), secret);
        try (var context = ApplicationContext.run(config, "test")) {
            var backups = context.getBean(BackupService.class);
            assertThrows(IOException.class, backups::create);
            Files.delete(link);
            try (var archive = backups.create()) {
                assertFalse(unzip(archive.stream().readAllBytes()).containsKey("link.txt"));
            }
            assertEquals(1, context.getBean(MeterRegistry.class).get("wiki.backups").tag("result", "failure").counter().count());
        }
    }

    @Test void rejectsDatabaseOutsideDataInsteadOfReturningIncompleteBackup() throws Exception {
        Path root = directory.resolve("data");
        var config = config(root);
        config.put("datasources.default.url", "jdbc:h2:file:" + directory.resolve("elsewhere"));
        try (var context = ApplicationContext.run(config, "test")) {
            assertThrows(IOException.class, () -> context.getBean(BackupService.class).create());
        }
    }

    @Test void rejectsAttachmentsOutsideDataInsteadOfReturningIncompleteBackup() throws Exception {
        var config = config(directory.resolve("data"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        config.put("wiki.files-directory", outside.toString());
        try (var context = ApplicationContext.run(config, "test")) {
            assertThrows(IOException.class, () -> context.getBean(BackupService.class).create());
        }
    }

    @Test void backupWaitsForWriteMonitorAndRejectsConcurrentGeneration() throws Exception {
        var config = config(directory.resolve("data"));
        try (var context = ApplicationContext.run(config, "test");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var backups = context.getBean(BackupService.class);
            var pages = context.getBean(PageService.class);
            java.util.concurrent.Future<BackupService.Archive> pending;
            synchronized (pages) {
                var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
                pending = executor.submit(() -> {
                    thread.set(Thread.currentThread());
                    return backups.create();
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while ((thread.get() == null || thread.get().getState() != Thread.State.BLOCKED) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertNotNull(thread.get());
                assertEquals(Thread.State.BLOCKED, thread.get().getState());
                assertFalse(pending.isDone());
                var error = assertThrows(HttpStatusException.class, backups::create);
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
            }
            try (var archive = pending.get(10, TimeUnit.SECONDS)) {
                assertTrue(unzip(archive.stream().readAllBytes()).containsKey("wiki.mv.db"));
            }
        }
    }

    private static String basic() {
        return "Basic " + java.util.Base64.getEncoder().encodeToString("admin:admin112".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        var entries = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNull(entries.put(entry.getName(), zip.readAllBytes()), "duplicate ZIP entry");
            }
        }
        return entries;
    }
}
