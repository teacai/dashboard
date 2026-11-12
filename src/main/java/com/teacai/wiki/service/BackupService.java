package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Singleton
public class BackupService {
    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy_MM_dd");
    private final DataSource dataSource;
    private final PageService pages;
    private final WikiProperties properties;
    private final Path attachments;
    private final MeterRegistry metrics;
    private final DataMaintenance maintenance;

    public BackupService(DataSource dataSource, PageService pages, WikiProperties properties,
                         AttachmentStorage storage, MeterRegistry metrics, DataMaintenance maintenance) {
        this.dataSource = dataSource;
        this.pages = pages;
        this.properties = properties;
        this.attachments = storage.root();
        this.metrics = metrics;
        this.maintenance = maintenance;
    }

    /** The caller must close the stream, including on a disconnected download. */
    public record Archive(InputStream stream, long size, String filename) implements AutoCloseable {
        @Override public void close() throws IOException { stream.close(); }
    }

    public Archive create() throws IOException, SQLException {
        var operation = maintenance.operation();
        var started = Timer.start(metrics);
        Path archive = null;
        Path snapshot = null;
        try {
            String filename = "backup_" + LocalDate.now().format(DATE) + ".zip";
            Path root = Path.of(properties.getDataDirectory()).toRealPath();
            if (root.getParent() == null) throw new IOException("The backup data directory cannot be the filesystem root");
            if (!attachments.startsWith(root)) throw new IOException("The attachment directory must be inside the backup data directory");
            archive = Files.createTempFile("wiki-backup-", ".zip");
            snapshot = Files.createTempFile("wiki-database-", ".zip");
            if (archive.toRealPath().startsWith(root) || snapshot.toRealPath().startsWith(root)) {
                throw new IOException("The JVM temporary directory must be outside the backup data directory");
            }
            LOG.info("Backup generation started");
            // All application installations, deletions and page writes use this same monitor.
            // Hold it through both the H2 snapshot and file copying, but not the HTTP download.
            synchronized (pages) {
                Path database = snapshotDatabase(root, snapshot);
                zip(root, database, snapshot, archive);
            }
            Files.delete(snapshot);
            snapshot = null;
            long size = Files.size(archive);
            // Delete on close also handles cancelled/failed HTTP transfers without retaining backups in /data.
            InputStream stream = Files.newInputStream(archive, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE);
            archive = null;
            metrics.counter("wiki.backups", "result", "success").increment();
            LOG.info("Backup generated: bytes={}", size);
            return new Archive(stream, size, filename);
        } catch (IOException | SQLException | RuntimeException e) {
            metrics.counter("wiki.backups", "result", "failure").increment();
            LOG.error("Backup generation failed", e);
            throw e;
        } finally {
            try {
                deleteTemporary(snapshot);
                deleteTemporary(archive);
            } finally {
                operation.close();
                started.stop(metrics.timer("wiki.backup.duration"));
            }
        }
    }

    private void deleteTemporary(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException e) { LOG.warn("Unable to delete temporary backup file: {}", path, e); }
    }

    private Path snapshotDatabase(Path root, Path target) throws SQLException, IOException {
        try (var connection = dataSource.getConnection();
             var query = connection.createStatement();
             var result = query.executeQuery("CALL DATABASE_PATH()")) {
            result.next();
            String base = result.getString(1);
            if (base == null) throw new IOException("Backups require a file-backed H2 database");
            Path database = Path.of(base + ".mv.db").toRealPath();
            if (!database.startsWith(root)) throw new IOException("The database must be inside the backup data directory");
            try (var backup = connection.prepareStatement("BACKUP TO ?")) {
                backup.setString(1, target.toString());
                backup.execute();
            }
            return database;
        }
    }

    private void zip(Path root, Path database, Path snapshot, Path target) throws IOException {
        Path lock = database.resolveSibling(database.getFileName().toString().replaceFirst("\\.mv\\.db$", ".lock.db"));
        try (var saved = new ZipFile(snapshot.toFile());
             var output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry databaseEntry = saved.getEntry(database.getFileName().toString());
            if (databaseEntry == null) throw new IOException("H2 backup is missing the database file");
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (directory.getParent().equals(root) && RestoreService.isRecoveryDirectory(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!directory.equals(root)) {
                        output.putNextEntry(new ZipEntry(name(directory) + "/"));
                        output.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) throw new IOException("Backup cannot include symbolic links or special files");
                    // H2's process lock is runtime state, not restorable application data.
                    if (file.equals(lock)) {
                        return FileVisitResult.CONTINUE;
                    }
                    var entry = new ZipEntry(name(file));
                    entry.setLastModifiedTime(attrs.lastModifiedTime());
                    output.putNextEntry(entry);
                    try (var input = file.equals(database) ? saved.getInputStream(databaseEntry)
                            : Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        input.transferTo(output);
                    }
                    output.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                private String name(Path path) { return root.relativize(path).toString().replace('\\', '/'); }
            });
        }
    }
}
