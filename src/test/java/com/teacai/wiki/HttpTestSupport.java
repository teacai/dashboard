package com.teacai.wiki;

import org.jsoup.Jsoup;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

final class HttpTestSupport implements AutoCloseable {
    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    HttpTestSupport(URI base) { this.base = base; }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    Map<String, String> editor(String path) throws Exception {
        var response = get(path.equals("/") ? "/editor" : path + "/editor");
        org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode(), response.body());
        var doc = Jsoup.parse(response.body());
        var fields = new LinkedHashMap<String, String>();
        fields.put("csrf", doc.selectFirst("input[name=csrf]").val());
        fields.put("version", doc.selectFirst("input[name=version]").val());
        fields.put("content", doc.selectFirst("textarea").wholeText());
        fields.put("password", "");
        return fields;
    }

    HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Origin", origin)
                .header("Referer", origin + (path.equals("/") ? "/editor" : path + "/editor"))
                .header("Sec-Fetch-Site", "same-origin")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    @Override public void close() { client.close(); }
}
