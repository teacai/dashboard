package com.teacai.wiki.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("wiki")
public class WikiProperties {
    private String name = "Wiki";
    private long maxPageSize = 104857600;
    private String masterPassword = "";
    private boolean secureCookies;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(long maxPageSize) {
        if (maxPageSize < 1 || maxPageSize > Integer.MAX_VALUE / 3) {
            throw new IllegalArgumentException("wiki.max-page-size must be between 1 and 715827882 bytes");
        }
        this.maxPageSize = maxPageSize;
    }
    public boolean isSecureCookies() { return secureCookies; }
    public void setSecureCookies(boolean secureCookies) { this.secureCookies = secureCookies; }
    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String masterPassword) { this.masterPassword = masterPassword; }
}
