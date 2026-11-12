package com.teacai.wiki.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("wiki")
public class WikiProperties {
    private String name = "Wiki";
    private long maxPageSize = 104857600;
    private String masterPassword = "";
    private boolean secureCookies;
    private String filesDirectory = "./data/files";
    private long maxUploadSize = 2L * 1024 * 1024 * 1024;

    public String getFilesDirectory() { return filesDirectory; }
    public void setFilesDirectory(String filesDirectory) {
        if (filesDirectory == null || filesDirectory.isBlank()) throw new IllegalArgumentException("wiki.files-directory must not be blank");
        this.filesDirectory = filesDirectory;
    }
    public long getMaxUploadSize() { return maxUploadSize; }
    public void setMaxUploadSize(long size) {
        if (size < 1 || size > 2L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("wiki.max-upload-size must be between 1 byte and 2 GiB");
        }
        maxUploadSize = size;
    }

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
