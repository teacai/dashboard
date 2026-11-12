package com.teacai.wiki.model;

/** Internal database row. Numeric IDs and password hashes never leave the server. */
public record Page(String path, String content, String passwordHash, long version) {
    public boolean isProtected() { return passwordHash != null && !passwordHash.isBlank(); }
}
