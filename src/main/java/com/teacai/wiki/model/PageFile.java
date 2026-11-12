package com.teacai.wiki.model;

import java.util.UUID;

public record PageFile(UUID publicId, String pagePath, String relativePath, String storageKey, long sizeBytes) {
    public String url() { return "/_files/" + publicId; }
    public String filename() { return relativePath.substring(relativePath.lastIndexOf('/') + 1); }

    public static UUID newPublicId() {
        UUID random = UUID.randomUUID();
        return new UUID((System.currentTimeMillis() << 16) | 0x7000 | (random.getMostSignificantBits() & 0xfff),
                random.getLeastSignificantBits());
    }
}
