package com.teacai.wiki.controller;

import com.teacai.wiki.service.AdminAuth;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

@Order(-200)
@ServerFilter("/**")
public class BackupAuthFilter {
    private final AdminAuth auth;

    public BackupAuthFilter(AdminAuth auth) { this.auth = auth; }

    @RequestFilter
    @Nullable
    public HttpResponse<?> authenticate(HttpRequest<?> request) {
        // Match decoded paths here, not just literal route patterns: encoded aliases must also be guarded.
        // Query strings never affect whether authentication is required.
        String path = request.getUri().normalize().getPath();
        if (inNamespace(path, "/_admin")) return auth.checkCredentials(request);
        // Browser admin forms verify CSRF in their controller; the backup API remains CLI-only for POSTs.
        if (inNamespace(path, "/_backup")) return auth.check(request);
        return null;
    }

    private boolean inNamespace(String path, String namespace) {
        return path != null && (path.equals(namespace) || path.startsWith(namespace + "/"));
    }
}
