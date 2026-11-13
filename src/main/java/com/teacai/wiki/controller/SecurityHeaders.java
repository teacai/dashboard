package com.teacai.wiki.controller;

import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

@ServerFilter("/**")
public class SecurityHeaders {
    @ResponseFilter
    public void headers(MutableHttpResponse<?> response) {
        if (!response.getHeaders().contains("Content-Security-Policy")) {
            response.header("Content-Security-Policy", "default-src 'none'; style-src 'self'; script-src 'self'; connect-src 'self'; "
                    + "img-src 'self' https: http:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        }
        response.header("X-Content-Type-Options", "nosniff");
        response.header("X-Frame-Options", "DENY");
        // no-referrer makes browser form POSTs send Origin: null, which Micronaut rejects as cross-origin.
        // Keep same-origin form submissions working without leaking referrers to other origins.
        response.header("Referrer-Policy", "same-origin");
        response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        // Only successful asset/download handlers opt in to browser caching. Errors and dynamic views
        // retain no-store even if a framework error response copied headers from the original response.
        int status = response.code();
        if ((status != 200 && status != 304) || !response.getHeaders().contains("Cache-Control")) {
            response.header("Cache-Control", "no-store");
        }
    }
}
