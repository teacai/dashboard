package com.teacai.wiki.service;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;

/** Request stamps can be released by the request-termination thread, unlike thread-owned locks. */
@Singleton
public class DataMaintenance {
    private final Semaphore operations = new Semaphore(1);
    private final StampedLock requests = new StampedLock();
    private volatile boolean restoring;
    private volatile boolean unavailable;

    public Lease operation() {
        if (unavailable || !operations.tryAcquire()) throw busy();
        return operations::release;
    }

    public long admitRequest() {
        if (restoring || unavailable) return 0;
        long stamp = requests.tryReadLock();
        if (stamp != 0 && (restoring || unavailable)) {
            requests.unlockRead(stamp);
            return 0;
        }
        return stamp;
    }

    public void releaseRequest(long stamp) { requests.unlockRead(stamp); }

    public Lease exclusive() {
        restoring = true;
        try {
            long stamp = requests.tryWriteLock(30, TimeUnit.SECONDS);
            if (stamp == 0) throw busy();
            return () -> {
                requests.unlockWrite(stamp);
                restoring = false;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            restoring = false;
            throw busy();
        } catch (RuntimeException e) {
            restoring = false;
            throw e;
        }
    }

    /** Fail closed if even rollback cannot reopen the previous database. */
    public void failClosed() { unavailable = true; }
    public boolean isUnavailable() { return unavailable; }

    public interface Lease extends AutoCloseable { @Override void close(); }

    private static HttpStatusException busy() {
        return new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Backup/restore maintenance is busy or unavailable. Try again later.");
    }
}
