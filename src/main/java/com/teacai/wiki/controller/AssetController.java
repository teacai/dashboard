package com.teacai.wiki.controller;

import com.teacai.wiki.service.ContentCache;
import com.teacai.wiki.http.BrowserCache;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller
public class AssetController {
    private final ContentCache cache;

    private final Map<String, String> etags;

    public AssetController(ContentCache cache) throws IOException {
        this.cache = cache;
        var validators = new HashMap<String, String>();
        for (String path : new String[]{"/static/js/editor.js", "/static/css/style.css", "/static/favicon.ico"}) {
            try (var input = AssetController.class.getResourceAsStream(path)) {
                if (input == null) throw new IOException("Missing static asset: " + path);
                validators.put(path, BrowserCache.etag(input.readAllBytes()));
            }
        }
        etags = Map.copyOf(validators);
    }

    @Get(value = "/_assets/editor.js", produces = "text/javascript")
    public HttpResponse<StreamedFile> editorScript(HttpRequest<?> request) throws IOException {
        return asset(request, "/static/js/editor.js", "text/javascript");
    }

    @Get(value = "/_assets/style.css", produces = "text/css")
    public HttpResponse<StreamedFile> stylesheet(HttpRequest<?> request) throws IOException {
        return asset(request, "/static/css/style.css", "text/css");
    }

    @Get(value = "/favicon.ico", produces = "image/x-icon")
    public HttpResponse<StreamedFile> favicon(HttpRequest<?> request) throws IOException {
        return asset(request, "/static/favicon.ico", "image/x-icon");
    }

    private HttpResponse<StreamedFile> asset(HttpRequest<?> request, String path, String type) throws IOException {
        String etag = etags.get(path);
        if (BrowserCache.matches(request.getHeaders().getAll("If-None-Match"), etag)) {
            return HttpResponse.<StreamedFile>status(HttpStatus.NOT_MODIFIED)
                    .header("Cache-Control", BrowserCache.CONTROL).header("ETag", etag);
        }
        return HttpResponse.ok(content(path, type))
                .header("Cache-Control", BrowserCache.CONTROL).header("ETag", etag);
    }

    private StreamedFile content(String path, String type) throws IOException {
        String key = "asset:" + path;
        byte[] bytes = cache.get(key, byte[].class);
        if (bytes == null) {
            var resource = AssetController.class.getResource(path);
            var connection = resource.openConnection();
            long size = connection.getContentLengthLong();
            if (!cache.isEnabled() || size < 0 || size >= Integer.MAX_VALUE || !cache.canCache(key, size)) {
                return new StreamedFile(connection.getInputStream(), MediaType.of(type), -1, size);
            }
            try (var input = connection.getInputStream()) { bytes = input.readNBytes((int) size + 1); }
            if (bytes.length != size) throw new IOException("Static asset size changed");
            cache.put(key, bytes, bytes.length);
        }
        return new StreamedFile(new ByteArrayInputStream(bytes), MediaType.of(type), -1, bytes.length);
    }
}
