package com.teacai.wiki.controller;

import com.teacai.wiki.service.DataMaintenance;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;

import java.util.concurrent.atomic.AtomicLong;

@Order(-100)
@ServerFilter("/**")
public class MaintenanceFilter implements ApplicationEventListener<HttpRequestTerminatedEvent> {
    private static final String STAMP = MaintenanceFilter.class.getName();
    private final DataMaintenance maintenance;

    public MaintenanceFilter(DataMaintenance maintenance) { this.maintenance = maintenance; }

    @RequestFilter
    @Nullable
    public HttpResponse<?> admit(HttpRequest<?> request) {
        // Restore acquires the exclusive lease itself, after the uploaded ZIP has been staged/validated.
        if (request.getMethod() == HttpMethod.POST && request.getPath().equals("/_backup")) return null;
        long stamp = maintenance.admitRequest();
        if (stamp == 0) return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1");
        request.setAttribute(STAMP, new AtomicLong(stamp));
        return null;
    }

    @Override public void onApplicationEvent(HttpRequestTerminatedEvent event) {
        event.getSource().getAttribute(STAMP, AtomicLong.class).ifPresent(lease -> {
            long stamp = lease.getAndSet(0);
            if (stamp != 0) maintenance.releaseRequest(stamp);
        });
    }
}
