package com.teacai.wiki.repository;

import com.teacai.wiki.model.Page;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;

@Singleton
public class PageRepository {
    private final DataSource dataSource;

    public PageRepository(DataSource dataSource) { this.dataSource = dataSource; }

    public Optional<Page> findByPath(String path) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT path, content, password_hash, version FROM wiki_pages WHERE path = ?")) {
            statement.setString(1, path);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new Page(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getLong(4))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read page", e);
        }
    }

    /** Authorization uses the version subsequently checked by this atomic write. */
    public boolean save(Page page, long expectedVersion) {
        String sql = expectedVersion < 0
                ? "INSERT INTO wiki_pages(path, content, password_hash, version) VALUES (?, ?, ?, 0)"
                : "UPDATE wiki_pages SET content = ?, password_hash = ?, version = version + 1, "
                    + "updated_at = CURRENT_TIMESTAMP WHERE path = ? AND version = ?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            if (expectedVersion < 0) {
                statement.setString(1, page.path());
                statement.setString(2, page.content());
                statement.setString(3, page.passwordHash());
            } else {
                statement.setString(1, page.content());
                statement.setString(2, page.passwordHash());
                statement.setString(3, page.path());
                statement.setLong(4, expectedVersion);
            }
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) return false; // Concurrent creation.
            throw new IllegalStateException("Unable to save page", e);
        }
    }
}
