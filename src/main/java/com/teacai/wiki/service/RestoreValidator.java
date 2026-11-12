package com.teacai.wiki.service;

import jakarta.inject.Singleton;
import org.flywaydb.core.api.MigrationVersion;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class RestoreValidator {
    public record Migration(String version, Integer checksum, boolean success) {}

    /** Only these controlled compatibility diagnostics are safe to return to the administrator. */
    public static final class IncompatibleBackupException extends IOException {
        public IncompatibleBackupException(String message) { super(message); }
    }

    public boolean requiresMigration(List<Migration> applied, List<Migration> expected,
                                     MigrationVersion latestAvailable) throws IncompatibleBackupException {
        for (var migration : applied) {
            if (migration.version() == null) continue; // Flyway's schema-history creation marker.
            MigrationVersion version;
            try { version = MigrationVersion.fromVersion(migration.version()); }
            catch (RuntimeException e) { throw new IncompatibleBackupException("Backup has an invalid database migration version."); }
            if (version.compareTo(latestAvailable) > 0) {
                throw new IncompatibleBackupException("Backup was created with a newer application database version. Upgrade the application before restoring it.");
            }
        }
        // Older backups must be an exact successful prefix, not a divergent, edited, or incomplete history.
        if (applied.isEmpty() || applied.stream().noneMatch(m -> m.version() != null)
                || applied.stream().anyMatch(m -> !m.success()) || expected.stream().anyMatch(m -> !m.success())
                || applied.size() > expected.size() || !expected.subList(0, applied.size()).equals(applied)) {
            throw new IncompatibleBackupException("Backup database migration history is incompatible: migrations are missing, modified, or failed.");
        }
        return applied.size() < expected.size();
    }

    public List<Migration> migrations(Connection connection) throws SQLException {
        var result = new ArrayList<Migration>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT \"version\", \"checksum\", \"success\" FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"")) {
            while (rows.next()) result.add(new Migration(rows.getString(1), rows.getObject(2, Integer.class), rows.getBoolean(3)));
        }
        return List.copyOf(result);
    }

    public void validate(Connection connection, Path attachments, List<Migration> expected) throws SQLException, IOException {
        if (!migrations(connection).equals(expected) || expected.stream().anyMatch(m -> !m.success())) {
            throw new IOException("Backup schema/migration checksums do not match this application");
        }
        if (!Files.isDirectory(attachments, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup is missing its files directory");
        // Read all persisted page content, not just SELECT 1: this also exercises H2's CLOB storage.
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT path, content, password_hash, version FROM wiki_pages")) {
            while (rows.next()) {
                if (rows.getString(1) == null || rows.getLong(4) < 0) throw new IOException("Invalid page metadata");
                rows.getString(3);
                try (var content = rows.getCharacterStream(2)) {
                    if (content == null) throw new IOException("Missing page content");
                    content.transferTo(Writer.nullWriter());
                }
            }
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT f.public_id, f.page_path, f.relative_path, f.storage_key, f.size_bytes, p.id "
                     + "FROM wiki_files f LEFT JOIN wiki_pages p ON p.path = f.page_path")) {
            while (rows.next()) {
                if (rows.getObject(1, java.util.UUID.class) == null || rows.getObject(6) == null) {
                    throw new IOException("Invalid attachment ownership");
                }
                String key;
                try { key = AttachmentStorage.storageKey(rows.getString(2), rows.getString(3)); }
                catch (RuntimeException e) { throw new IOException("Invalid attachment path", e); }
                Path file = attachments.resolve(key).normalize();
                if (!key.equals(rows.getString(4)) || !file.startsWith(attachments)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != rows.getLong(5)) {
                    throw new IOException("Backup attachment is missing or has an invalid size/path");
                }
            }
        }
    }
}
