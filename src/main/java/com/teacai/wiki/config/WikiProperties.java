package com.teacai.wiki.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("wiki")
public class WikiProperties {
    private String name = "Wiki";
    private long maxPageSize = 104857600;
    private String masterPassword = "";
    private boolean secureCookies;
    private String dataDirectory = "./data";
    private String filesDirectory = "./data/files";
    private long maxUploadSize = 2L * 1024 * 1024 * 1024;
    private String adminUsername = "admin";
    private String adminPassword = "admin112";
    private long maxRestoreSize = 20L * 1024 * 1024 * 1024;

    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String username) {
        if (username == null || username.isBlank() || username.contains(":")) {
            throw new IllegalArgumentException("wiki.admin-username must not be blank or contain a colon");
        }
        adminUsername = username;
    }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String password) {
        if (password == null || password.isBlank()) throw new IllegalArgumentException("wiki.admin-password must not be blank");
        adminPassword = password;
    }
    public long getMaxRestoreSize() { return maxRestoreSize; }
    public void setMaxRestoreSize(long size) {
        if (size < 1) throw new IllegalArgumentException("wiki.max-restore-size must be positive");
        maxRestoreSize = size;
    }

    public String getDataDirectory() { return dataDirectory; }
    public void setDataDirectory(String dataDirectory) {
        if (dataDirectory == null || dataDirectory.isBlank()) throw new IllegalArgumentException("wiki.data-directory must not be blank");
        this.dataDirectory = dataDirectory;
    }

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
