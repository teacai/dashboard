package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.model.PageFile;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Filesystem work only. Never resolve a client-provided path without validating every segment. */
@Singleton
public class AttachmentStorage {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AttachmentStorage.class);
    private final Path root;
    private final long limit;

    public AttachmentStorage(WikiProperties properties) throws IOException {
        Path configured = Path.of(properties.getFilesDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        root = configured.toRealPath();
        if (root.getParent() == null) throw new IllegalArgumentException("wiki.files-directory cannot be the filesystem root");
        limit = properties.getMaxUploadSize();
    }

    public static String storageKey(String page, String relative) {
        validateRelative(relative);
        // %00 cannot occur in a valid canonical page path. Preserve empty/trailing URL segments.
        String slug = page.equals("/") ? "" : String.join("/",
                java.util.Arrays.stream(page.substring(1).split("/", -1)).map(s -> s.isEmpty() ? "%00" : s).toList());
        String key = slug.isEmpty() ? relative : slug + "/" + relative;
        validatePath(key, false);
        return key;
    }

    public static void validateRelative(String path) { validatePath(path, true); }

    private static void validatePath(String path, boolean uploadPath) {
        if (path == null || path.isEmpty() || path.length() > 20000 || path.indexOf('\\') >= 0) throw invalid();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.getBytes(StandardCharsets.UTF_8).length > 255
                    || segment.chars().anyMatch(c -> c < 32 || c == 127 || uploadPath && c == ':')) throw invalid();
        }
    }

    public Path resolve(String key) throws IOException {
        validatePath(key, false);
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw invalid();
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw invalid();
        }
        return target;
    }

    public InputStream open(PageFile file) throws IOException {
        Path path = resolve(file.storageKey());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }

    public Batch stage(List<Source> sources) throws IOException {
        Batch batch = new Batch();
        try {
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < sources.size(); i++) {
                Source source = sources.get(i);
                if (source.size() < 0 || source.size() > limit - total) throw tooLarge();
                Path temporary = batch.directory.resolve("upload-" + i);
                long copied = 0;
                try (InputStream in = source.stream().open(); var out = Files.newOutputStream(temporary)) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (count > limit - total - copied) throw tooLarge();
                        out.write(buffer, 0, count);
                        copied += count;
                    }
                }
                if (copied != source.size()) throw new IOException("Upload size changed during staging");
                total += copied;
                batch.staged.add(temporary);
            }
            return batch;
        } catch (IOException | RuntimeException e) {
            try { batch.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public Batch removal() throws IOException { return new Batch(); }

    public final class Batch implements AutoCloseable {
        private final Path directory = Files.createTempDirectory(root.getParent(), ".wiki-upload-");
        private final List<Path> staged = new ArrayList<>();
        private final List<Change> changes = new ArrayList<>();
        private boolean committed;

        private Batch() throws IOException {}

        public void install(List<PageFile> files) throws IOException {
            Set<Object> installedKeys = new HashSet<>();
            var installedPaths = new ArrayList<Path>();
            for (int i = 0; i < files.size(); i++) {
                Path target = resolve(files.get(i).storageKey());
                createParents(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Object key = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                    boolean alias = key != null && installedKeys.contains(key);
                    if (key == null) {
                        for (Path previous : installedPaths) alias |= Files.isSameFile(previous, target);
                    }
                    if (alias) throw new HttpStatusException(HttpStatus.CONFLICT, "Uploaded paths refer to the same filesystem file");
                }
                backup(target);
                Files.move(staged.get(i), target);
                installedKeys.add(Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey());
                installedPaths.add(target);
            }
        }

        public void remove(PageFile file) throws IOException { backup(resolve(file.storageKey())); }

        private void backup(Path target) throws IOException {
            Path backup = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw invalid();
                backup = directory.resolve("backup-" + changes.size());
                Files.move(target, backup);
            }
            changes.add(new Change(target, backup));
        }

        private void createParents(Path parent) throws IOException {
            Path current = root;
            for (Path segment : root.relativize(parent)) {
                current = current.resolve(segment);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw invalid();
                } else Files.createDirectory(current);
            }
        }

        public void commit() { committed = true; }

        @Override public void close() throws IOException {
            if (!committed) {
                for (Change change : changes.reversed()) {
                    Files.deleteIfExists(change.target());
                    if (change.backup() != null) Files.move(change.backup(), change.target());
                }
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException e) {
                if (!committed) throw e;
                // The DB and installed files already committed. Do not report a false failed upload.
                LOG.warn("Unable to clean committed upload staging directory", e);
            }
        }
    }

    public record Source(String relativePath, long size, StreamSource stream) {}
    @FunctionalInterface public interface StreamSource { InputStream open() throws IOException; }
    private record Change(Path target, Path backup) {}

    private static HttpStatusException invalid() {
        return new HttpStatusException(HttpStatus.BAD_REQUEST, "Unsafe or unsupported file path");
    }
    private static HttpStatusException tooLarge() {
        return new HttpStatusException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Files exceed the upload byte limit");
    }
}
