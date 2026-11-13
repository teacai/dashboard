package com.teacai.wiki.service;

import com.teacai.wiki.config.WikiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;

/** Shared FIFO payload budget. Hits do not change insertion order; the home page is pinned. */
@Singleton
public class ContentCache {
    private static final long MB = 1024 * 1024;
    private static final String HOME = "page:/";
    private final long maxEntryBytes;
    private final long maxBytes;
    private final MeterRegistry metrics;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private long bytes;

    public ContentCache(WikiProperties properties, MeterRegistry metrics) {
        maxEntryBytes = properties.getCacheMaxFileSizeMb() * MB;
        maxBytes = properties.getCacheMaxSizeMb() * MB;
        this.metrics = metrics;
        metrics.gauge("wiki.cache.bytes", this, ContentCache::sizeBytes);
        metrics.gauge("wiki.cache.entries", this, ContentCache::size);
        LoggerFactory.getLogger(ContentCache.class).info("Content cache: enabled={}, maxFileMB={}, maxMB={}",
                isEnabled(), properties.getCacheMaxFileSizeMb(), properties.getCacheMaxSizeMb());
    }

    public boolean isEnabled() { return maxEntryBytes > 0 && maxBytes > 0; }

    public boolean canCache(String key, long size) {
        return isEnabled() && size >= 0 && (HOME.equals(key) || size <= maxEntryBytes && size <= maxBytes);
    }

    public synchronized <T> T get(String key, Class<T> type) {
        if (!isEnabled()) return null;
        Entry entry = entries.get(key);
        metrics.counter("wiki.cache.requests", "result", entry == null ? "miss" : "hit").increment();
        return entry == null ? null : type.cast(entry.value());
    }

    public synchronized void put(String key, Object value, long size) {
        remove(key);
        if (!canCache(key, size)) return;
        // Charge even empty payloads so arbitrarily many empty files cannot bypass the budget.
        long weight = Math.max(1, size);
        Entry home = entries.get(HOME);
        if (!HOME.equals(key) && home != null && home.bytes() > maxBytes - weight) return;
        var iterator = entries.entrySet().iterator();
        while (bytes > maxBytes - weight && iterator.hasNext()) {
            var oldest = iterator.next();
            if (HOME.equals(oldest.getKey())) continue;
            bytes -= oldest.getValue().bytes();
            iterator.remove();
            metrics.counter("wiki.cache.evictions").increment();
        }
        entries.put(key, new Entry(value, weight));
        bytes += weight;
    }

    public synchronized void remove(String key) {
        Entry previous = entries.remove(key);
        if (previous != null) bytes -= previous.bytes();
    }

    public synchronized void clear() {
        entries.clear();
        bytes = 0;
    }

    public synchronized long sizeBytes() { return bytes; }
    public synchronized int size() { return entries.size(); }

    private record Entry(Object value, long bytes) {}
}
