package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Singleton
public class RestoreService {
    private static final Logger LOG = LoggerFactory.getLogger(RestoreService.class);
    private final DataSource dataSource;
    private final WikiProperties properties;
    private final AttachmentStorage storage;
    private final PageService pages;
    private final DataMaintenance maintenance;
    private final RestoreArchive archives;
    private final RestoreValidator validator;
    private final MeterRegistry metrics;

    public RestoreService(DataSource dataSource, WikiProperties properties, AttachmentStorage storage, PageService pages,
                          DataMaintenance maintenance, RestoreArchive archives, RestoreValidator validator, MeterRegistry metrics) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.storage = storage;
        this.pages = pages;
        this.maintenance = maintenance;
        this.archives = archives;
        this.validator = validator;
        this.metrics = metrics;
    }

    public void restore(InputStream input) throws IOException, SQLException {
        try (var operation = maintenance.operation()) {
            var started = Timer.start(metrics);
            Path workspace = null;
            try {
                Layout layout = layout();
                workspace = Files.createTempDirectory(layout.root(), ".wiki-restore-");
                Path contents;
                try {
                    contents = archives.extract(input, workspace);
                    validateStaged(contents, layout);
                } catch (RestoreValidator.IncompatibleBackupException e) {
                    LOG.warn("Restore archive rejected: {}", e.getMessage());
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid backup: " + e.getMessage() + " Current data is unchanged.");
                } catch (IOException | SQLException | RuntimeException e) {
                    LOG.warn("Restore archive rejected: validation or staged migration failed", e);
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                            "Invalid backup: ZIP, database, schema migration, or attachment validation failed. Current data is unchanged.");
                }
                LOG.info("Restore archive validated; waiting for active requests to finish");
                try (var exclusive = maintenance.exclusive()) {
                    synchronized (pages) {
                        replace(contents, layout);
                        pages.resetCache();
                    }
                }
                metrics.counter("wiki.restores", "result", "success").increment();
                LOG.info("Backup restored successfully");
            } catch (IOException | SQLException | RuntimeException e) {
                metrics.counter("wiki.restores", "result", "failure").increment();
                if (!(e instanceof HttpStatusException)) LOG.error("Backup restore failed", e);
                throw e;
            } finally {
                // If rollback failed, preserve every recovery artifact for manual repair.
                if (workspace != null && !maintenance.isUnavailable()) cleanup(workspace);
                started.stop(metrics.timer("wiki.restore.duration"));
            }
        }
    }

    private record Layout(Path root, Path database, Path attachments, HikariDataSource pool,
                          List<RestoreValidator.Migration> migrations) {}

    private Layout layout() throws IOException, SQLException {
        Path root = Path.of(properties.getDataDirectory()).toRealPath();
        if (root.getParent() == null || !storage.root().startsWith(root)) {
            throw new IOException("Database and attachments must be inside a non-root data directory");
        }
        var pool = dataSource.unwrap(HikariDataSource.class);
        if (!pool.isAllowPoolSuspension()) throw new IOException("Restore requires datasources.default.allow-pool-suspension=true");
        try (var connection = pool.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("CALL DATABASE_PATH()")) {
            result.next();
            String base = result.getString(1);
            if (base == null) throw new IOException("Restore requires a file-backed H2 database");
            Path database = Path.of(base + ".mv.db").toRealPath();
            if (!database.startsWith(root)) throw new IOException("Database must be inside the data directory");
            return new Layout(root, root.relativize(database), root.relativize(storage.root()), pool, validator.migrations(connection));
        }
    }

    private void validateStaged(Path contents, Layout layout) throws IOException, SQLException {
        Path database = contents.resolve(layout.database());
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup is missing its database");
        String base = database.toString().substring(0, database.toString().length() - ".mv.db".length());
        // All writes/migrations happen in the extracted copy, never in the current application database.
        // Do not reuse arbitrary JDBC URL options or allow H2 to create an empty database on a missing path.
        String url = "jdbc:h2:file:" + base + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        try (var connection = DriverManager.getConnection(url, layout.pool().getUsername(), layout.pool().getPassword())) {
            try {
                var flyway = Flyway.configure().dataSource(url, layout.pool().getUsername(), layout.pool().getPassword())
                        .locations("classpath:db/migration").failOnMissingLocations(true)
                        .validateOnMigrate(true).validateMigrationNaming(true)
                        .ignoreMigrationPatterns(new String[0]) // Never ignore future/missing migrations.
                        .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load();
                var latest = Arrays.stream(flyway.info().all()).filter(info -> info.getState().isResolved())
                        .map(MigrationInfo::getVersion).filter(java.util.Objects::nonNull)
                        .max(java.util.Comparator.naturalOrder())
                        .orElseThrow(() -> new IOException("No application database migrations were found"));
                boolean older = validator.requiresMigration(validator.migrations(connection), layout.migrations(), latest);
                // Flyway also validates applied scripts against the resources shipped by this application.
                var migrated = flyway.migrate();
                if (migrated.migrationsExecuted > 0) {
                    metrics.counter("wiki.restore.migrations").increment(migrated.migrationsExecuted);
                    LOG.info("Staged backup database upgraded: migrations={}, target={}", migrated.migrationsExecuted, latest);
                }
                Path attachments = contents.resolve(layout.attachments());
                // Versions predating attachment storage may not have archived an empty files directory.
                if (older && !Files.exists(attachments, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(attachments);
                validator.validate(connection, attachments, layout.migrations());
            } finally {
                // A backed-up DB_CLOSE_DELAY setting must not keep the staging database open during renames.
                try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
            }
        }
    }

    private void replace(Path contents, Layout layout) throws IOException, SQLException {
        Path previous = Files.createTempDirectory(layout.root(), ".wiki-previous-");
        var moved = new ArrayList<Path>();
        var installed = new ArrayList<Path>();
        boolean recovered = false;
        LOG.info("Restore installation starting: staged={}, previous={}", contents, previous);
        try {
            suspendAndClose(layout.pool());
            // Move the children, not the root: /data itself may be a Docker volume mount point.
            for (Path current : children(layout.root())) {
                if (isRecoveryDirectory(current)) continue;
                Path name = current.getFileName();
                Files.move(current, previous.resolve(name), StandardCopyOption.ATOMIC_MOVE);
                moved.add(name);
            }
            for (Path staged : children(contents)) {
                Path name = staged.getFileName();
                Files.move(staged, layout.root().resolve(name), StandardCopyOption.ATOMIC_MOVE);
                installed.add(name);
            }
            layout.pool().getHikariPoolMXBean().resumePool();
            validateLive(layout);
            recovered = true;
        } catch (IOException | SQLException | RuntimeException failure) {
            LOG.warn("Restored database could not be installed/opened; rolling back to previous data");
            try {
                try { suspendAndClose(layout.pool()); }
                catch (SQLException e) {
                    // A corrupt replacement may not open at all. Evict any remaining idle handles before rollback.
                    layout.pool().getHikariPoolMXBean().suspendPool();
                    layout.pool().getHikariPoolMXBean().softEvictConnections();
                }
                for (Path name : installed) Files.move(layout.root().resolve(name), contents.resolve(name), StandardCopyOption.ATOMIC_MOVE);
                for (Path name : moved) Files.move(previous.resolve(name), layout.root().resolve(name), StandardCopyOption.ATOMIC_MOVE);
                layout.pool().getHikariPoolMXBean().resumePool();
                validateLive(layout);
                recovered = true;
                metrics.counter("wiki.restores", "result", "rollback").increment();
                LOG.warn("Previous database and files restored after failed restore");
            } catch (IOException | SQLException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
                maintenance.failClosed();
                LOG.error("Restore rollback failed; application is unavailable. Preserve recovery directories: {}, {}", previous, contents.getParent(), rollback);
            }
            throw failure;
        } finally {
            if (recovered) {
                layout.pool().getHikariPoolMXBean().resumePool();
                cleanup(previous);
            }
        }
    }

    private void suspendAndClose(HikariDataSource pool) throws SQLException {
        // This is also called during rollback after a partial suspension. HTTP requests remain excluded.
        // Resume before borrowing: getConnection intentionally waits while the pool is suspended.
        pool.getHikariPoolMXBean().resumePool();
        var connection = pool.getConnection();
        pool.getHikariPoolMXBean().suspendPool();
        try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
        finally {
            // Mark the borrowed proxy evicted before close, so Hikari does not reset a connection H2 just closed.
            pool.getHikariPoolMXBean().softEvictConnections();
            connection.close();
        }
    }

    private void validateLive(Layout layout) throws SQLException, IOException {
        try (var connection = layout.pool().getConnection()) {
            validator.validate(connection, layout.root().resolve(layout.attachments()), layout.migrations());
        }
    }

    static boolean isRecoveryDirectory(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".wiki-restore-") || name.startsWith(".wiki-previous-");
    }

    private List<Path> children(Path directory) throws IOException {
        try (var paths = Files.list(directory)) { return paths.toList(); }
    }

    private void cleanup(Path path) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { LOG.warn("Unable to remove restore staging/recovery directory: {}", path, e); }
    }
}
