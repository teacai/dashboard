package com.teacai.wiki.service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Local Unicode mappings for the rxaviers emoji cheat sheet, including legacy aliases. */
final class EmojiShortcodes {
    private static final Map<String, String> EMOJI = load();
    // Bound candidate length so malformed input cannot cause unbounded scans after each colon.
    private static final Pattern SHORTCODE = Pattern.compile(":([a-z0-9_+\\-]{1,40}):");

    private EmojiShortcodes() {}

    static String replace(String text) {
        if (text.indexOf(':') < 0) return text;
        return SHORTCODE.matcher(text).replaceAll(match -> EMOJI.getOrDefault(match.group(1), match.group()));
    }

    private static Map<String, String> load() {
        try (var stream = EmojiShortcodes.class.getResourceAsStream("/emoji/shortcodes.properties")) {
            if (stream == null) throw new IllegalStateException("Missing bundled emoji shortcode mappings");
            var properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties.stringPropertyNames().stream()
                    .collect(Collectors.toUnmodifiableMap(name -> name, properties::getProperty));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load bundled emoji shortcode mappings", e);
        }
    }
}
