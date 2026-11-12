package com.teacai.wiki.repository;

import com.teacai.wiki.model.PageFile;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class FileRepository {
    private static final String COLUMNS = "public_id, page_path, relative_path, storage_key, size_bytes";
    private final DataSource dataSource;

    public FileRepository(DataSource dataSource) { this.dataSource = dataSource; }

    public List<PageFile> list(String pagePath) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM wiki_files WHERE page_path = ? ORDER BY relative_path")) {
            statement.setString(1, pagePath);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<PageFile>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw new IllegalStateException("Unable to list files", e); }
    }

    public Optional<PageFile> find(UUID id) { return find("public_id", id); }
    public Optional<PageFile> findStorageKey(String key) { return find("storage_key", key); }

    private Optional<PageFile> find(String column, Object value) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM wiki_files WHERE " + column + " = ?")) {
            statement.setObject(1, value);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        } catch (SQLException e) { throw new IllegalStateException("Unable to find file", e); }
    }

    public void save(Connection connection, PageFile file) throws SQLException {
        try (var statement = connection.prepareStatement("MERGE INTO wiki_files (" + COLUMNS
                + ", updated_at) KEY(public_id) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
            statement.setObject(1, file.publicId());
            statement.setString(2, file.pagePath());
            statement.setString(3, file.relativePath());
            statement.setString(4, file.storageKey());
            statement.setLong(5, file.sizeBytes());
            statement.executeUpdate();
        }
    }

    public void delete(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM wiki_files WHERE public_id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    public void transaction(SqlWork work) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (Exception e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Unable to update file metadata", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Unable to open file transaction", e); }
    }

    private PageFile read(ResultSet rows) throws SQLException {
        return new PageFile(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5));
    }

    @FunctionalInterface public interface SqlWork { void run(Connection connection) throws Exception; }
}
