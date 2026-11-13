package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.service.ContentCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ContentCacheTest {
    private static final long MB = 1024 * 1024;

    private ContentCache cache(long maxFileMb, long maxMb) {
        var properties = new WikiProperties();
        properties.setCacheMaxFileSizeMb(maxFileMb);
        properties.setCacheMaxSizeMb(maxMb);
        return new ContentCache(properties, new SimpleMeterRegistry());
    }

    @Test void defaultsAreInMegabytesAndInvalidSizesAreRejected() {
        var properties = new WikiProperties();
        assertEquals(1, properties.getCacheMaxFileSizeMb());
        assertEquals(50, properties.getCacheMaxSizeMb());
        assertThrows(IllegalArgumentException.class, () -> properties.setCacheMaxFileSizeMb(-1));
        assertThrows(IllegalArgumentException.class, () -> properties.setCacheMaxSizeMb(-1));
        assertThrows(IllegalArgumentException.class, () -> properties.setCacheMaxSizeMb(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> properties.setCacheMaxFileSizeMb(Long.MAX_VALUE));
    }

    @Test void evictsOldestAcrossAllContentTypesWithoutPromotingHits() {
        var cache = cache(1, 2);
        cache.put("page:/first", "first", MB);
        cache.put("file:second", "second", MB);
        assertEquals("first", cache.get("page:/first", String.class));
        cache.put("asset:third", "third", MB);
        assertNull(cache.get("page:/first", String.class));
        assertEquals("second", cache.get("file:second", String.class));
        assertEquals("third", cache.get("asset:third", String.class));
        assertEquals(2 * MB, cache.sizeBytes());
    }

    @Test void rejectsOversizedEntriesWithoutEvictingOtherEntriesAndHonorsExactBoundary() {
        var cache = cache(1, 2);
        cache.put("file:first", "first", MB);
        cache.put("file:large", "large", MB + 1);
        assertEquals(1, cache.size());
        assertNull(cache.get("file:large", String.class));
        cache.put("file:first", "now too large", MB + 1);
        assertEquals(0, cache.sizeBytes());
        var smallBudget = cache(3, 1);
        smallBudget.put("file:large", "large", 2 * MB);
        assertEquals(0, smallBudget.size());
    }

    @Test void replacementRefreshesAgeAndAccountsForNewWeight() {
        var cache = cache(1, 2);
        cache.put("file:first", "old", MB);
        cache.put("file:second", "second", MB);
        cache.put("file:first", "new", MB);
        cache.put("file:third", "third", MB);
        assertNull(cache.get("file:second", String.class));
        assertEquals("new", cache.get("file:first", String.class));
        cache.remove("file:first");
        assertEquals(MB, cache.sizeBytes());
        cache.clear();
        assertEquals(0, cache.sizeBytes());
        assertEquals(0, cache.size());
    }

    @Test void keepsHomePinnedEvenWhenLargerThanEitherLimit() {
        var cache = cache(1, 2);
        cache.put("page:/", "home", MB / 2);
        cache.put("file:first", "first", MB);
        cache.put("file:second", "second", MB);
        assertEquals("home", cache.get("page:/", String.class));
        assertNull(cache.get("file:first", String.class));
        cache.put("page:/", "large home", 3 * MB);
        assertEquals(1, cache.size());
        assertEquals(3 * MB, cache.sizeBytes());
        cache.put("file:third", "third", 1);
        assertEquals(1, cache.size());
        cache.put("page:/", "smaller home", MB / 2);
        cache.put("file:third", "third", MB);
        assertEquals(2, cache.size());
    }

    @Test void eitherZeroDisablesEverythingIncludingHome() {
        for (var cache : new ContentCache[]{cache(0, 50), cache(1, 0), cache(0, 0)}) {
            assertFalse(cache.isEnabled());
            assertFalse(cache.canCache("page:/", 0));
            cache.put("page:/", "home", 0);
            cache.put("file:empty", "empty", 0);
            assertNull(cache.get("page:/", String.class));
            assertEquals(0, cache.sizeBytes());
            assertEquals(0, cache.size());
        }
    }

    @Test void emptyEntriesAreChargedAndConcurrentAdmissionsStayWithinBudget() throws Exception {
        var cache = cache(1, 1);
        cache.put("file:empty", "empty", 0);
        assertEquals(1, cache.sizeBytes());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 100; i++) {
                String key = "file:" + i;
                tasks.add(executor.submit(() -> {
                    cache.put(key, key, MB / 4);
                    cache.get(key, String.class);
                }));
            }
            for (var task : tasks) task.get();
        }
        assertEquals(MB, cache.sizeBytes());
        assertEquals(4, cache.size());
    }
}
