package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.repository.FileRepository;
import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.PageService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
class FileWorkflowTest {
    @Inject FileService files;
    @Inject PageService pages;
    @Inject FileRepository repository;
    @Inject AttachmentStorage storage;
    @Inject WikiProperties properties;

    private AttachmentStorage.Source source(String relative, String contents) {
        byte[] bytes = contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new AttachmentStorage.Source(relative, bytes.length, () -> new ByteArrayInputStream(bytes));
    }
    private String path() { return "/workflow/" + UUID.randomUUID(); }

    @Test void rollsBackPageMetadataAndReplacementsWhenPartOfTheFilesystemBatchFails() throws Exception {
        String path = path();
        files.upload(path, "original draft", "", -1, List.of(source("file.txt", "original bytes")));
        var before = pages.getPage(path);
        var attachment = repository.list(path).getFirst();
        var error = assertThrows(HttpStatusException.class, () -> files.upload(path, "must not publish", "", before.version(),
                List.of(source("file.txt", "replacement"), source("file.txt/child", "impossible directory"))));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals(before, pages.getPage(path));
        assertEquals(List.of(attachment), repository.list(path));
        assertEquals("original bytes", Files.readString(storage.resolve(attachment.storageKey())));
    }

    @Test void versionChangeWhileStagingRejectsOldAuthorizationAndLeavesNoFile() throws Exception {
        String path = path();
        pages.savePage(path, "original", "", -1);
        long version = pages.getPage(path).version();
        var changingSource = new AttachmentStorage.Source("file.txt", 3, () -> {
            // The snapshot was authenticated before staging. Another editor publishes while I/O is in progress.
            pages.savePage(path, "newer revision", "", version);
            return new ByteArrayInputStream(new byte[]{1, 2, 3});
        });
        var error = assertThrows(HttpStatusException.class, () -> files.upload(path, "stale draft", "", version, List.of(changingSource)));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("newer revision", pages.getPage(path).content());
        assertTrue(repository.list(path).isEmpty());
        assertFalse(Files.exists(storage.resolve(AttachmentStorage.storageKey(path, "file.txt"))));
    }

    @Test void appendedLinksAreIncludedInTheMarkdownSizeLimitBeforeFilesAreInstalled() throws Exception {
        long previous = properties.getMaxPageSize();
        String path = path();
        try {
            properties.setMaxPageSize(32);
            var error = assertThrows(HttpStatusException.class, () -> files.upload(path, "draft", "", -1, List.of(source("file.txt", "data"))));
            assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, error.getStatus());
            assertEquals(-1, pages.getPage(path).version());
            assertTrue(repository.list(path).isEmpty());
        } finally { properties.setMaxPageSize(previous); }
    }
}
