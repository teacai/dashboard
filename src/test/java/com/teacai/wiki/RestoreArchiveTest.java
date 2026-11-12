package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.service.RestoreArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestoreArchiveTest {
    @TempDir Path directory;

    private Path extract(Map<String, byte[]> entries, long limit) throws IOException {
        var properties = new WikiProperties();
        properties.setMaxRestoreSize(limit);
        return new RestoreArchive(properties).extract(new ByteArrayInputStream(RestoreTest.zip(entries)),
                Files.createTempDirectory(directory, "extract-"));
    }

    @Test void rejectsTraversalAbsoluteBackslashAndReservedRecoveryPaths() {
        for (String name : new String[]{"../outside", "/absolute", "nested/../../escape", "a\\b", "C:/drive",
                "a/./b", "a//b", ".wiki-restore-123/contents", ".wiki-previous-123/wiki.mv.db"}) {
            assertThrows(IOException.class, () -> extract(Map.of(name, new byte[]{1}), 10000), name);
        }
        assertFalse(Files.exists(directory.getParent().resolve("outside")));
    }

    @Test void limitsActualExpandedBytesInsteadOfTrustingCompressedSize() {
        assertThrows(IOException.class, () -> extract(Map.of("bomb", new byte[100_000]), 1000));
    }

    @Test void rejectsFileDirectoryCollisions() {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("same", new byte[]{1});
        entries.put("same/child", new byte[]{2});
        assertThrows(IOException.class, () -> extract(entries, 10000));
    }

    @Test void rejectsMissingZipCentralDirectory() throws Exception {
        byte[] valid = RestoreTest.zip(Map.of("file", new byte[]{1, 2, 3}));
        var archive = new RestoreArchive(new WikiProperties());
        assertThrows(IOException.class, () -> archive.extract(new ByteArrayInputStream(Arrays.copyOf(valid, valid.length - 10)), directory));
    }

    @Test void includesEmptyDirectoriesUnicodeAndBinaryFiles() throws Exception {
        byte[] bytes = {0, 1, -1, 10};
        Path root = extract(Map.of("empty/", new byte[0], "nested/日本語.bin", bytes), 10000);
        assertTrue(Files.isDirectory(root.resolve("empty")));
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("nested/日本語.bin")));
    }
}
