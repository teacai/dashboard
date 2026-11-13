package com.teacai.wiki.http;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Validators for resources that may be stored, but must be revalidated before reuse. */
public final class BrowserCache {
    public static final String CONTROL = "public, no-cache";

    private BrowserCache() {}

    public static String etag(byte[] bytes) {
        try {
            return '"' + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** GET/HEAD If-None-Match uses weak comparison, including lists and the wildcard. */
    public static boolean matches(List<String> headers, String etag) {
        String expected = stripWeak(etag);
        for (String header : headers) {
            for (String value : header.split(",")) {
                String candidate = value.strip();
                if (candidate.equals("*") || stripWeak(candidate).equals(expected)) return true;
            }
        }
        return false;
    }

    private static String stripWeak(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }
}
