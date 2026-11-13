package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.model.Page;
import com.teacai.wiki.model.PageFile;
import com.teacai.wiki.repository.FileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.MediaType;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Singleton
public class FileService {
    public static final int MAX_FILES = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(FileService.class);
    private final PageService pages;
    private final FileRepository repository;
    private final AttachmentStorage storage;
    private final WikiProperties properties;
    private final MeterRegistry metrics;
    private final ContentCache cache;

    public FileService(PageService pages, FileRepository repository, AttachmentStorage storage,
                       WikiProperties properties, MeterRegistry metrics, ContentCache cache) {
        this.pages = pages;
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.metrics = metrics;
        this.cache = cache;
    }

    public List<PageFile> list(String path) { return repository.list(path); }

    public void upload(String pagePath, String draft, String password, long version,
                       List<AttachmentStorage.Source> sources) throws IOException {
        var started = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            List<PageFile> planned = plan(pagePath, sources);
            Page prepared = pages.preparePage(pagePath, appendLinks(draft, planned), password, version);
            // Bulk I/O stays outside the write monitor. A version check at commit rejects stale authorization.
            var batch = storage.stage(sources);
            synchronized (pages) {
                // Rollback must also finish under the monitor before another writer can install files.
                try (batch) {
                    repository.transaction(connection -> {
                        pages.persist(connection, prepared);
                        for (PageFile file : planned) checkOwnership(file);
                        batch.install(planned);
                        for (PageFile file : planned) repository.save(connection, file);
                    });
                    batch.commit();
                }
                for (PageFile file : planned) cache.remove("file:" + file.publicId());
                pages.saved(prepared);
            }
            long bytes = sources.stream().mapToLong(AttachmentStorage.Source::size).sum();
            metrics.counter("wiki.files.uploaded").increment(planned.size());
            metrics.counter("wiki.files.bytes").increment(bytes);
            LOG.info("Files uploaded: count={}, bytes={}", planned.size(), bytes);
        } catch (IOException | RuntimeException e) {
            metrics.counter("wiki.files.failures", "operation", "upload").increment();
            if (e instanceof HttpStatusException status) LOG.warn("File upload rejected: status={}", status.getStatus().getCode());
            else LOG.error("File upload failed", e);
            throw e;
        } finally { started.stop(metrics.timer("wiki.files.upload")); }
    }

    private List<PageFile> plan(String pagePath, List<AttachmentStorage.Source> sources) {
        if (sources.isEmpty() || sources.size() > MAX_FILES) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Select between 1 and 1000 files");
        }
        var seen = new HashSet<String>();
        var result = new ArrayList<PageFile>();
        long total = 0;
        for (var source : sources) {
            if (source.relativePath() == null || source.relativePath().length() > 4096) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "File path is too long");
            }
            String key = AttachmentStorage.storageKey(pagePath, source.relativePath());
            if (!seen.add(key)) throw conflict("The upload contains duplicate file paths");
            if (source.size() < 0 || source.size() > properties.getMaxUploadSize() - total) {
                throw new HttpStatusException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Files exceed the upload byte limit");
            }
            total += source.size();
            var existing = repository.findStorageKey(key);
            if (existing.isPresent() && !existing.get().pagePath().equals(pagePath)) {
                throw conflict("This file path belongs to another page");
            }
            result.add(new PageFile(existing.map(PageFile::publicId).orElseGet(PageFile::newPublicId),
                    pagePath, source.relativePath(), key, source.size()));
        }
        return List.copyOf(result);
    }

    private void checkOwnership(PageFile file) throws IOException {
        var existing = repository.findStorageKey(file.storageKey());
        if (existing.isPresent()) {
            if (!existing.get().pagePath().equals(file.pagePath()) || !existing.get().publicId().equals(file.publicId())) {
                throw conflict("The file changed while uploading. Reload the editor");
            }
        } else if (Files.exists(storage.resolve(file.storageKey()), LinkOption.NOFOLLOW_LINKS)) {
            // Never overwrite unmanaged files or a case-insensitive alias of another page's file.
            throw conflict("A file already exists at this storage path");
        }
    }

    /** Deletion keeps the current draft in the editor, but does not publish it or rewrite Markdown links. */
    public Page delete(String pagePath, UUID id, String password, long version) throws IOException {
        synchronized (pages) {
            Page existing = pages.getPage(pagePath);
            Page prepared = pages.preparePage(pagePath, existing.content(), password, version);
            PageFile file = repository.find(id).filter(f -> f.pagePath().equals(pagePath))
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "File not found"));
            try (var batch = storage.removal()) {
                repository.transaction(connection -> {
                    pages.persist(connection, prepared);
                    batch.remove(file);
                    repository.delete(connection, id);
                });
                batch.commit();
            }
            cache.remove("file:" + id);
            pages.saved(prepared);
            metrics.counter("wiki.files.deleted").increment();
            LOG.info("File deleted");
            return new Page(pagePath, prepared.content(), prepared.passwordHash(), prepared.version() + 1);
        }
    }

    public StreamedFile download(UUID id) throws IOException {
        synchronized (pages) {
            String key = "file:" + id;
            CachedFile cached = cache.get(key, CachedFile.class);
            if (cached != null) return cached.stream();
            PageFile file = repository.find(id)
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "File not found"));
            if (file.sizeBytes() < Integer.MAX_VALUE && cache.canCache(key, file.sizeBytes())) {
                byte[] bytes;
                try (var input = storage.open(file)) { bytes = input.readNBytes((int) file.sizeBytes() + 1); }
                if (bytes.length != file.sizeBytes()) throw new IOException("Attachment size changed on disk");
                cached = new CachedFile(file, bytes);
                cache.put(key, cached, bytes.length);
                return cached.stream();
            }
            return new StreamedFile(storage.open(file), MediaType.APPLICATION_OCTET_STREAM_TYPE,
                    -1, file.sizeBytes()).attach(file.filename());
        }
    }

    private record CachedFile(PageFile file, byte[] bytes) {
        StreamedFile stream() {
            return new StreamedFile(new ByteArrayInputStream(bytes), MediaType.APPLICATION_OCTET_STREAM_TYPE,
                    -1, bytes.length).attach(file.filename());
        }
    }

    public static String appendLinks(String draft, List<PageFile> files) {
        if (draft == null) throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Missing page content");
        var result = new StringBuilder(draft);
        if (!draft.isEmpty() && !draft.endsWith("\n\n")) result.append(draft.endsWith("\n") ? "\n" : "\n\n");
        for (PageFile file : files) result.append("- [").append(markdownLabel(file.relativePath()))
                .append("](").append(file.url()).append(")\n");
        return result.toString();
    }

    private static String markdownLabel(String value) {
        var escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c >= '!' && c <= '/' || c >= ':' && c <= '@' || c >= '[' && c <= '`' || c >= '{' && c <= '~') escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static HttpStatusException conflict(String message) { return new HttpStatusException(HttpStatus.CONFLICT, message); }
}
