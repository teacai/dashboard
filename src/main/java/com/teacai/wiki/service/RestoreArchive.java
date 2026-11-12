package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** Untrusted ZIP parsing happens entirely in a fresh private staging directory. */
@Singleton
public class RestoreArchive {
    private final long limit;

    public RestoreArchive(WikiProperties properties) { limit = properties.getMaxRestoreSize(); }

    public Path extract(InputStream input, Path workspace) throws IOException {
        Path archive = workspace.resolve("archive.zip");
        try (var output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
            copy(input, output, limit, null);
        }
        Path contents = Files.createDirectory(workspace.resolve("contents"));
        var names = new HashSet<String>();
        long remaining = limit;
        try (var zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 100_000) throw new IOException("The archive has too many entries");
                String name = entry.getName();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                validateName(name);
                if (!names.add(name)) throw new IOException("Duplicate archive path");
                Path target = contents.resolve(name).normalize();
                if (!target.startsWith(contents)) throw new IOException("Archive path escapes staging directory");
                if (entry.isDirectory()) {
                    if (entry.getSize() != 0 || entry.getCrc() != 0) throw new IOException("Invalid directory entry");
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    var crc = new CRC32();
                    try (var source = zip.getInputStream(entry);
                         var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        remaining -= copy(source, output, remaining, crc);
                    }
                    if (crc.getValue() != entry.getCrc()) throw new IOException("Archive entry checksum mismatch");
                }
            }
        }
        return contents;
    }

    private void validateName(String name) throws IOException {
        if (name.isEmpty() || name.length() > 20000 || name.contains("\\") || name.contains(":")) {
            throw new IOException("Invalid archive path");
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.chars().anyMatch(c -> c < 32 || c == 127)) {
                throw new IOException("Invalid archive path");
            }
        }
        if (RestoreService.isRecoveryDirectory(Path.of(name.split("/", 2)[0]))) {
            throw new IOException("Archive contains reserved recovery directories");
        }
    }

    private long copy(InputStream input, OutputStream output, long maximum, CRC32 crc) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maximum - copied) throw new IOException("Archive exceeds wiki.max-restore-size");
            output.write(buffer, 0, count);
            if (crc != null) crc.update(buffer, 0, count);
            copied += count;
        }
        return copied;
    }
}
