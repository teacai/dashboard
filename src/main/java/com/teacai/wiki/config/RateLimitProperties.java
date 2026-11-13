package com.teacai.wiki.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("wiki.rate-limit")
public class RateLimitProperties {
    private int requestsPerMinute = 60;
    private boolean trustForwardedHeaders;

    public boolean isTrustForwardedHeaders() { return trustForwardedHeaders; }
    public void setTrustForwardedHeaders(boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    public int getRequestsPerMinute() { return requestsPerMinute; }

    public void setRequestsPerMinute(int requestsPerMinute) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("wiki.rate-limit.requests-per-minute must be positive");
        }
        this.requestsPerMinute = requestsPerMinute;
    }
}
