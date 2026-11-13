package com.teacai.wiki.controller;

import com.teacai.wiki.service.ContentCache;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller
public class AssetController {
    private final ContentCache cache;

    public AssetController(ContentCache cache) { this.cache = cache; }

    @Get(value = "/_assets/editor.js", produces = "text/javascript")
    public HttpResponse<StreamedFile> editorScript() throws IOException {
        return asset("/static/js/editor.js", "text/javascript");
    }

    @Get(value = "/_assets/style.css", produces = "text/css")
    public HttpResponse<StreamedFile> stylesheet() throws IOException {
        return asset("/static/css/style.css", "text/css");
    }

    @Get(value = "/favicon.ico", produces = "image/x-icon")
    public HttpResponse<StreamedFile> favicon() throws IOException {
        return asset("/static/favicon.ico", "image/x-icon");
    }

    private HttpResponse<StreamedFile> asset(String path, String type) throws IOException {
        String key = "asset:" + path;
        byte[] bytes = cache.get(key, byte[].class);
        if (bytes == null) {
            var resource = AssetController.class.getResource(path);
            if (!cache.isEnabled()) return HttpResponse.ok(new StreamedFile(resource));
            var connection = resource.openConnection();
            long size = connection.getContentLengthLong();
            if (size >= Integer.MAX_VALUE || !cache.canCache(key, size)) {
                return HttpResponse.ok(new StreamedFile(resource));
            }
            try (var input = connection.getInputStream()) { bytes = input.readNBytes((int) size + 1); }
            if (bytes.length != size) throw new IOException("Static asset size changed");
            cache.put(key, bytes, bytes.length);
        }
        return HttpResponse.ok(new StreamedFile(new ByteArrayInputStream(bytes), MediaType.of(type), -1, bytes.length));
    }
}
