package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.model.Page;
import com.teacai.wiki.repository.PageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.runtime.event.annotation.EventListener;
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
    private final ContentCache cache;
    private final MarkdownRenderer markdown;
    private final String masterHash;
    private double authTokens = 10;
    private long lastRefill = System.nanoTime();

    public PageService(PageRepository repository, PasswordService passwords,
                       WikiProperties properties, MeterRegistry metrics, ContentCache cache, MarkdownRenderer markdown) {
        this.repository = repository;
        this.passwords = passwords;
        this.properties = properties;
        this.metrics = metrics;
        this.cache = cache;
        this.markdown = markdown;
        String master = properties.getMasterPassword();
        masterHash = master == null || master.isBlank() ? null : passwords.hashPassword(master);
    }

    public boolean usesMasterPassword() { return masterHash != null; }

    @EventListener
    public void warmHome(StartupEvent event) {
        if (cache.isEnabled()) getPage("/");
    }

    public synchronized Page getPage(String path) { return load(path, false).page(); }

    public synchronized RenderedPage getRenderedPage(String path) { return load(path, true); }

    private RenderedPage load(String path, boolean needsHtml) {
        RenderedPage cached = cache.get("page:" + path, RenderedPage.class);
        if (cached != null) return cached;
        Page page = metrics.timer("wiki.database.read").record(() -> repository.findByPath(path)
                .orElse(new Page(path, "", null, -1)));
        return renderAndCache(page, needsHtml);
    }

    private RenderedPage renderAndCache(Page page, boolean needsHtml) {
        String key = "page:" + page.path();
        long sourceBytes = cache.isEnabled() ? page.content().getBytes(StandardCharsets.UTF_8).length : 0;
        // Do not retain arbitrary missing URLs, or render oversized drafts just to read their metadata.
        boolean candidate = (page.version() >= 0 || page.path().equals("/")) && cache.canCache(key, sourceBytes);
        String html = candidate || needsHtml
                ? metrics.timer("wiki.markdown.render").record(() -> markdown.render(page.content())) : null;
        var rendered = new RenderedPage(page, html);
        if (candidate) cache.put(key, rendered, sourceBytes + html.getBytes(StandardCharsets.UTF_8).length);
        return rendered;
    }

    /** Restore replaces both the database and attachments while holding the page write monitor. */
    public synchronized void resetCache() {
        cache.clear();
        if (cache.isEnabled()) getPage("/");
    }

    public record RenderedPage(Page page, String html) {}

    public void savePage(String path, String content, String password, long expectedVersion) {
        commit(preparePage(path, content, password, expectedVersion));
    }

    /** Save the draft and replace/remove protection in one version-checked database write. */
    public Page changePassword(String path, String content, String oldPassword, String newPassword,
                               String confirmation, long expectedVersion) {
        try {
            if (usesMasterPassword()) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN,
                        "Page password changes are disabled while a master password is configured.");
            }
            if (newPassword == null || confirmation == null || newPassword.length() > 1024
                    || confirmation.length() > 1024) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Enter and confirm a new password (at most 1024 characters).");
            }
            if (!newPassword.equals(confirmation)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "New passwords do not match. Your draft has not been saved.");
            }
            Page prepared = preparePage(path, content, oldPassword, expectedVersion, true);
            String hash = newPassword.isEmpty() ? null : passwords.hashPassword(newPassword);
            Page updated = new Page(path, prepared.content(), hash, prepared.version());
            commit(updated);
            metrics.counter("wiki.password.changes", "result", "success").increment();
            LOG.info("Page password changed: protected={}", updated.isProtected());
            return new Page(path, updated.content(), updated.passwordHash(), updated.version() + 1);
        } catch (HttpStatusException e) {
            metrics.counter("wiki.password.changes", "result", "rejected").increment();
            LOG.warn("Page password change rejected: status={}", e.getStatus().getCode());
            throw e;
        }
    }

    private void commit(Page updated) {
        synchronized (this) {
            if (!metrics.timer("wiki.database.write").record(() -> repository.save(updated, updated.version()))) conflict();
            saved(updated);
        }
    }

    /** Authenticate and validate before staging files; recheck the version during the atomic write. */
    public Page preparePage(String path, String content, String password, long expectedVersion) {
        return preparePage(path, content, password, expectedVersion, false);
    }

    private Page preparePage(String path, String content, String password, long expectedVersion, boolean passwordChange) {
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
        if (passwordChange || requiredHash != null || (password != null && !password.isEmpty())) checkAuthBudget();
        if (requiredHash != null && !passwords.verifyPassword(password, requiredHash)) {
            metrics.counter("wiki.saves", "result", "forbidden").increment();
            LOG.warn("Page save rejected: invalid password");
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Incorrect password. Your draft is preserved in the editor.");
        }
        if (existing.version() != expectedVersion) conflict();
        String hash = pageHash;
        // A single password field either sets protection or authenticates existing protection.
        if (!passwordChange && masterHash == null && hash == null && password != null && !password.isEmpty()) {
            hash = passwords.hashPassword(password);
        }
        return new Page(path, content, hash, expectedVersion);
    }

    /** Called under this service's monitor, with the file metadata in the same DB transaction. */
    public void persist(Connection connection, Page prepared) {
        if (!repository.save(connection, prepared, prepared.version())) conflict();
    }

    public synchronized void saved(Page page) {
        cache.remove("page:" + page.path());
        renderAndCache(new Page(page.path(), page.content(), page.passwordHash(), page.version() + 1), false);
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
