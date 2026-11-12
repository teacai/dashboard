package com.teacai.wiki.controller;

import com.teacai.wiki.service.AdminAuth;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

@Order(-200)
@ServerFilter({"/_backup", "/_backup/"})
public class BackupAuthFilter {
    private final AdminAuth auth;

    public BackupAuthFilter(AdminAuth auth) { this.auth = auth; }

    @RequestFilter
    @Nullable
    public HttpResponse<?> authenticate(HttpRequest<?> request) {
        return auth.check(request);
    }
}
