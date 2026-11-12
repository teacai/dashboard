package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.model.Page;
import com.teacai.wiki.repository.PageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

@Singleton
public class PageService {
    private static final Logger LOG = LoggerFactory.getLogger(PageService.class);
    private final PageRepository repository;
    private final PasswordService passwords;
    private final WikiProperties properties;
    private final MeterRegistry metrics;
    private final String masterHash;
    private double authTokens = 10;
    private long lastRefill = System.nanoTime();

    public PageService(PageRepository repository, PasswordService passwords,
                       WikiProperties properties, MeterRegistry metrics) {
        this.repository = repository;
        this.passwords = passwords;
        this.properties = properties;
        this.metrics = metrics;
        String master = properties.getMasterPassword();
        masterHash = master == null || master.isBlank() ? null : passwords.hashPassword(master);
    }

    public boolean usesMasterPassword() { return masterHash != null; }

    public Page getPage(String path) {
        return metrics.timer("wiki.database.read").record(() -> repository.findByPath(path)
                .orElse(new Page(path, "", null, -1)));
    }

    public void savePage(String path, String content, String password, long expectedVersion) {
        Page updated = preparePage(path, content, password, expectedVersion);
        synchronized (this) {
            if (!metrics.timer("wiki.database.write").record(() -> repository.save(updated, expectedVersion))) conflict();
        }
        saved(updated);
    }

    /** Authenticate and validate before staging files; recheck the version during the atomic write. */
    public Page preparePage(String path, String content, String password, long expectedVersion) {
        if (content == null) throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Missing page content");
        if (content.length() > properties.getMaxPageSize()
                || content.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPageSize()) {
            throw new HttpStatusException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Page exceeds the UTF-8 byte limit");
        }
        if (password != null && password.length() > 1024) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 1024 characters");
        }
        Page existing = getPage(path);
        String pageHash = existing.isProtected() ? existing.passwordHash() : null;
        // A configured master password overrides page passwords; otherwise public/new pages need none.
        String requiredHash = masterHash != null ? masterHash : pageHash;
        if (requiredHash != null || (password != null && !password.isEmpty())) checkAuthBudget();
        if (requiredHash != null && !passwords.verifyPassword(password, requiredHash)) {
            metrics.counter("wiki.saves", "result", "forbidden").increment();
            LOG.warn("Page save rejected: invalid password");
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Incorrect password. Your draft is preserved below.");
        }
        if (existing.version() != expectedVersion) conflict();
        String hash = pageHash;
        // A single password field either sets protection or authenticates existing protection.
        if (masterHash == null && hash == null && password != null && !password.isEmpty()) {
            hash = passwords.hashPassword(password);
        }
        return new Page(path, content, hash, expectedVersion);
    }

    /** Called under this service's monitor, with the file metadata in the same DB transaction. */
    public void persist(Connection connection, Page prepared) {
        if (!repository.save(connection, prepared, prepared.version())) conflict();
    }

    public void saved(Page page) {
        metrics.counter("wiki.saves", "result", "success").increment();
        LOG.info("Page saved: version={}, characters={}, protected={}", page.version() + 1,
                page.content().length(), masterHash != null || page.isProtected());
    }

    private void conflict() {
        metrics.counter("wiki.saves", "result", "conflict").increment();
        throw new HttpStatusException(HttpStatus.CONFLICT,
                "This page changed after you opened the editor. Copy your draft below before reloading.");
    }

    /** Bounded, process-wide token bucket: no attacker-controlled maps or unbounded password work. */
    private synchronized void checkAuthBudget() {
        long now = System.nanoTime();
        authTokens = Math.min(10, authTokens + (now - lastRefill) / 1_000_000_000.0);
        lastRefill = now;
        if (authTokens < 1) {
            metrics.counter("wiki.saves", "result", "throttled").increment();
            throw new HttpStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many password attempts. Try again shortly.");
        }
        authTokens--;
    }
}
