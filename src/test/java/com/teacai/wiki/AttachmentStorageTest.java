package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.model.PageFile;
import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.MarkdownRenderer;
import io.micronaut.http.exceptions.HttpStatusException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentStorageTest {
    @TempDir Path directory;

    private AttachmentStorage storage(long limit) throws Exception {
        var properties = new WikiProperties();
        properties.setFilesDirectory(directory.resolve("files").toString());
        properties.setMaxUploadSize(limit);
        return new AttachmentStorage(properties);
    }
    private AttachmentStorage.Source source(String name, String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new AttachmentStorage.Source(name, bytes.length, () -> new ByteArrayInputStream(bytes));
    }
    private PageFile file(String relative) {
        return new PageFile(PageFile.newPublicId(), "/page/nested", relative, "page/nested/" + relative, 3);
    }

    @Test void mirrorsPageAndDirectoryStructureWithoutCollapsingEncodedOrEmptySegments() {
        assertEquals("notes/topic/tree/sub/a.txt", AttachmentStorage.storageKey("/notes/topic", "tree/sub/a.txt"));
        assertEquals("root.txt", AttachmentStorage.storageKey("/", "root.txt"));
        assertEquals("a/%00/b/file", AttachmentStorage.storageKey("/a//b", "file"));
        assertEquals("a/%00/file", AttachmentStorage.storageKey("/a/", "file"));
        assertEquals("a%2Fb/file", AttachmentStorage.storageKey("/a%2Fb", "file"));
        assertEquals("a/folder/日本 [notes].txt", AttachmentStorage.storageKey("/a", "folder/日本 [notes].txt"));
    }

    @Test void rejectsUnsafePathsAndSymlinkAncestorsOrTargets() throws Exception {
        var storage = storage(100);
        for (String path : List.of("../evil", "/absolute", "a/../evil", "a/./evil", "a//b", "a\\b", "evil\u0000.txt", "evil\n.txt")) {
            assertThrows(HttpStatusException.class, () -> storage.resolve(path), path);
        }
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.createSymbolicLink(directory.resolve("files/link"), outside);
        assertThrows(HttpStatusException.class, () -> storage.resolve("link/evil.txt"));
        Files.writeString(outside.resolve("data.txt"), "safe");
        Files.createSymbolicLink(directory.resolve("files/target.txt"), outside.resolve("data.txt"));
        assertThrows(HttpStatusException.class, () -> storage.resolve("target.txt"));
    }

    @Test void rollsBackReplacementsAndPartialBatchFailures() throws Exception {
        var storage = storage(100);
        Path old = storage.resolve("page/nested/a.txt");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "original");
        try (var batch = storage.stage(List.of(source("a.txt", "new")))) {
            batch.install(List.of(file("a.txt")));
            assertEquals("new", Files.readString(old));
            // Simulate a failed database commit by not committing the filesystem batch.
        }
        assertEquals("original", Files.readString(old));
        try (var batch = storage.stage(List.of(source("a.txt", "new"), source("a.txt/child", "bad")))) {
            assertThrows(HttpStatusException.class, () -> batch.install(List.of(file("a.txt"), file("a.txt/child"))));
        }
        assertEquals("original", Files.readString(old));
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".wiki-upload-")));
        }
    }

    @Test void rejectsCaseAliasesWithinOneBatchOnCaseInsensitiveFilesystems() throws Exception {
        Path probe = Files.writeString(directory.resolve("case-probe"), "probe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(probe.resolveSibling("CASE-PROBE")));
        var storage = storage(100);
        try (var batch = storage.stage(List.of(source("Case.txt", "one"), source("case.txt", "two")))) {
            var error = assertThrows(HttpStatusException.class, () -> batch.install(List.of(file("Case.txt"), file("case.txt"))));
            assertEquals(409, error.getStatus().getCode());
        }
        assertFalse(Files.exists(storage.resolve("page/nested/Case.txt")));
    }

    @Test void commitsReplacementsAndRollsBackDeletions() throws Exception {
        var storage = storage(100);
        var file = file("a.txt");
        try (var batch = storage.stage(List.of(source("a.txt", "new")))) {
            batch.install(List.of(file));
            batch.commit();
        }
        try (var batch = storage.removal()) {
            batch.remove(file);
            assertFalse(Files.exists(storage.resolve(file.storageKey())));
        }
        assertEquals("new", Files.readString(storage.resolve(file.storageKey())));
        try (var batch = storage.removal()) { batch.remove(file); batch.commit(); }
        assertFalse(Files.exists(storage.resolve(file.storageKey())));
    }

    @Test void countsActualStreamBytesAndSupportsLongLimitsWithoutAllocatingGigabytes() throws Exception {
        var storage = storage(10);
        assertThrows(HttpStatusException.class, () -> storage.stage(List.of(source("one", "123456"), source("two", "12345"))));
        var dishonest = new AttachmentStorage.Source("one", 1, () -> new ByteArrayInputStream(new byte[11]));
        assertThrows(HttpStatusException.class, () -> storage.stage(List.of(dishonest)));
        var large = new AttachmentStorage.Source("large", 2L * 1024 * 1024 * 1024 + 1,
                () -> { throw new AssertionError("Oversized upload must be rejected without opening the stream"); });
        assertThrows(HttpStatusException.class, () -> storage(2L * 1024 * 1024 * 1024).stage(List.of(large)));
        assertThrows(IllegalArgumentException.class, () -> new WikiProperties().setMaxUploadSize(large.size()));
        try (var batch = storage.stage(List.of(source("one", "123456"), source("two", "1234")))) {
            batch.install(List.of(file("one"), file("two")));
            batch.commit();
        }
    }

    @Test void generatedMarkdownEscapesFilenamesAndPreservesDraftWhitespace() {
        var attachment = file("folder/日本 [x](evil) & `code`!.txt");
        String markdown = FileService.appendLinks("# Draft\n\n  text  ", List.of(attachment));
        assertTrue(markdown.startsWith("# Draft\n\n  text  \n\n- ["));
        var html = Jsoup.parse(new MarkdownRenderer().render(markdown));
        assertEquals(1, html.select("li a").size());
        assertEquals(attachment.relativePath(), html.selectFirst("li a").text());
        assertEquals(attachment.url(), html.selectFirst("li a").attr("href"));
        assertTrue(html.select("li code").isEmpty());
        assertEquals(7, attachment.publicId().version());
    }
}
