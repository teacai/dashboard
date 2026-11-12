package com.teacai.wiki;

import com.teacai.wiki.controller.MaintenanceFilter;
import com.teacai.wiki.service.DataMaintenance;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;
import io.micronaut.http.exceptions.HttpStatusException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DataMaintenanceTest {
    @Test void terminationReleasesRequestStampExactlyOnceAndRestoreExcludesOtherRequests() {
        var maintenance = new DataMaintenance();
        var filter = new MaintenanceFilter(maintenance);
        var request = HttpRequest.GET("/page");
        assertNull(filter.admit(request));
        filter.onApplicationEvent(new HttpRequestTerminatedEvent(request));
        filter.onApplicationEvent(new HttpRequestTerminatedEvent(request));
        try (var operation = maintenance.operation(); var exclusive = maintenance.exclusive()) {
            assertEquals(503, filter.admit(HttpRequest.GET("/page")).code());
            assertEquals(503, filter.admit(HttpRequest.GET("/_files/id")).code());
            assertNull(filter.admit(HttpRequest.POST("/_backup", "zip")));
            assertThrows(HttpStatusException.class, maintenance::operation);
        }
        var after = HttpRequest.GET("/page");
        assertNull(filter.admit(after));
        filter.onApplicationEvent(new HttpRequestTerminatedEvent(after));
    }

    @Test void exclusiveLeaseWaitsForExistingRequestAndRejectsNewRequestsWhileDraining() throws Exception {
        var maintenance = new DataMaintenance();
        long existing = maintenance.admitRequest();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(maintenance::exclusive);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean draining = false;
            while (System.nanoTime() < deadline) {
                long stamp = maintenance.admitRequest();
                if (stamp == 0) { draining = true; break; }
                maintenance.releaseRequest(stamp);
                Thread.sleep(5);
            }
            assertTrue(draining);
            assertFalse(pending.isDone());
            maintenance.releaseRequest(existing);
            try (var exclusive = pending.get(5, TimeUnit.SECONDS)) { assertEquals(0, maintenance.admitRequest()); }
            long after = maintenance.admitRequest();
            assertNotEquals(0, after);
            maintenance.releaseRequest(after);
        }
    }

    @Test void unrecoverableFailureKeepsRequestsAndFurtherOperationsUnavailable() {
        var maintenance = new DataMaintenance();
        maintenance.failClosed();
        assertEquals(0, maintenance.admitRequest());
        assertThrows(HttpStatusException.class, maintenance::operation);
    }
}
