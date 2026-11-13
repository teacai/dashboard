package com.teacai.wiki.controller;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;

import java.net.URI;

/** Keep encoded reserved characters (notably %2F, %3F and %23) distinct from separators. */
public final class PagePaths {
    private PagePaths() {}

    public static String canonical(URI uri) {
        String raw = URI.create(uri.toASCIIString()).getRawPath();
        if (raw == null || raw.isEmpty()) return "/";
        var path = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) throw invalid();
                int high = Character.digit(raw.charAt(i + 1), 16);
                int low = Character.digit(raw.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw invalid();
                char decoded = (char) (high * 16 + low);
                if (decoded <= 31 || decoded == 127) throw invalid();
                if (isUnreserved(decoded)) path.append(decoded);
                else path.append('%').append(Character.toUpperCase(raw.charAt(i + 1)))
                        .append(Character.toUpperCase(raw.charAt(i + 2)));
                i += 2;
            } else {
                if (c <= 31 || c == 127 || c == '\\') throw invalid();
                path.append(c);
            }
        }
        String result = path.toString();
        String pagePath = isEditor(result) ? page(result) : result;
        int maxLength = 16000 + (isEditor(result) ? "/editor".length() : 0)
                + (isMarkdown(pagePath) ? ".md".length() : 0);
        if (!result.startsWith("/") || result.length() > maxLength) throw invalid();
        for (String segment : result.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) throw invalid();
        }
        // Application-owned namespaces must never become user pages.
        if (result.equals("/_assets") || result.startsWith("/_assets/")
                || result.equals("/_files") || result.startsWith("/_files/")
                || result.equals("/_admin") || result.startsWith("/_admin/")
                || result.equals("/_backup") || result.startsWith("/_backup/")) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        return result;
    }

    /** A leading // must never become a protocol-relative form action, link or redirect. */
    public static String localUrl(String path) { return path.startsWith("//") ? "/." + path : path; }
    public static String editor(String path) { return path.equals("/") ? "/editor" : path + "/editor"; }
    public static boolean isEditor(String path) { return path.endsWith("/editor"); }
    public static String page(String editor) {
        String page = editor.substring(0, editor.length() - "/editor".length());
        return page.isEmpty() ? "/" : page;
    }

    public static boolean isMarkdown(String path) { return path.endsWith(".md"); }

    /** Resolve a canonical path: Markdown URLs are representations, not separate stored pages. */
    public static String htmlPage(String path) {
        int end = path.length();
        while (end >= 3 && path.startsWith(".md", end - 3)) end -= 3;
        if (end == path.length()) return path;
        // Validate again: removing a suffix can expose a reserved namespace or dot segment.
        return canonical(URI.create("http://wiki.invalid" + path.substring(0, end)));
    }

    public static String markdownFilename(String path) {
        String slugPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String slug = slugPath.substring(slugPath.lastIndexOf('/') + 1);
        return (slug.isEmpty() ? "index" : slug) + ".md";
    }

    private static boolean isUnreserved(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
    private static HttpStatusException invalid() {
        return new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid page path");
    }
}
