package com.teacai.wiki.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reject excess work before form binding, not after large request bodies have been buffered. */
@ServerFilter("/**")
public class RequestAdmission implements ApplicationEventListener<HttpRequestTerminatedEvent> {
    private static final String LEASE = RequestAdmission.class.getName();
    private final Semaphore slots = new Semaphore(2);
    private final MeterRegistry metrics;

    public RequestAdmission(MeterRegistry metrics) {
        this.metrics = metrics;
        metrics.gauge("wiki.work.active", slots, value -> 2 - value.availablePermits());
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> admit(HttpRequest<?> request) {
        if (request.getPath().equals("/_assets/style.css")) return null;
        if (!slots.tryAcquire()) {
            metrics.counter("wiki.requests.rejected").increment();
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1");
        }
        request.setAttribute(LEASE, new AtomicBoolean());
        return null;
    }

    @Override
    public void onApplicationEvent(HttpRequestTerminatedEvent event) {
        // Also runs for failed binding, oversized bodies and disconnected clients.
        event.getSource().getAttribute(LEASE, AtomicBoolean.class).ifPresent(released -> {
            if (released.compareAndSet(false, true)) slots.release();
        });
    }
}
