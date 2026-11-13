package com.teacai.wiki.controller;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.http.BrowserCache;
import com.teacai.wiki.model.Page;
import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.CsrfService;
import com.teacai.wiki.service.FileService;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.server.multipart.MultipartBody;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.views.View;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller("/_files")
@ExecuteOn(TaskExecutors.BLOCKING)
public class FileController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FileController.class);
    private static final Set<String> FIELDS = Set.of("page", "csrf", "password", "version", "content", "manifest");
    private final FileService files;
    private final CsrfService csrf;
    private final WikiController views;
    private final JsonMapper json;
    private final WikiProperties properties;

    public FileController(FileService files, CsrfService csrf, WikiController views,
                          JsonMapper json, WikiProperties properties) {
        this.files = files;
        this.csrf = csrf;
        this.views = views;
        this.json = json;
        this.properties = properties;
    }

    @Post(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA, produces = MediaType.TEXT_HTML)
    @View("page")
    public HttpResponse<?> upload(HttpRequest<?> request, @Body MultipartBody body) throws IOException {
        var parts = new ArrayList<CompletedPart>();
        try {
            // Parts are disk-spooled by Micronaut; only small metadata objects are collected here.
            Flux.from(body).doOnNext(part -> {
                parts.add(part);
                if (parts.size() > FileService.MAX_FILES + FIELDS.size()) throw bad("Too many multipart fields");
            }).then().block();
            var fields = new HashMap<String, String>();
            var uploads = new HashMap<String, CompletedFileUpload>();
            for (var part : parts) {
                if (part instanceof CompletedFileUpload file) {
                    if (uploads.put(file.getName(), file) != null) throw bad("Duplicate multipart file field");
                } else {
                    long limit = part.getName().equals("content") ? properties.getMaxPageSize() : 1024 * 1024;
                    if (!FIELDS.contains(part.getName()) || part.getSize() > limit || fields.containsKey(part.getName())) {
                        throw bad("Invalid multipart field");
                    }
                    fields.put(part.getName(), new String(part.getBytes(), StandardCharsets.UTF_8));
                }
            }
            String path = pagePath(fields.get("page"));
            csrf.verify(request, fields.get("csrf"));
            long version = version(fields);
            try {
                String manifest = fields.get("manifest");
                if (manifest == null) throw bad("Missing file manifest");
                String[] paths;
                try { paths = json.readValue(manifest, String[].class); }
                catch (IOException e) { throw bad("Invalid file manifest"); }
                if (paths == null || paths.length != uploads.size() || paths.length > FileService.MAX_FILES) throw bad("Invalid file manifest");
                var sources = new ArrayList<AttachmentStorage.Source>();
                for (int i = 0; i < paths.length; i++) {
                    CompletedFileUpload file = uploads.get("file" + i);
                    String relative = paths[i];
                    if (file == null || relative == null) throw bad("File manifest does not match the uploaded filenames");
                    String leaf = relative.substring(relative.lastIndexOf('/') + 1);
                    // HTML multipart encoding escapes literal quotes as %22, without escaping literal percent signs.
                    if (!leaf.equals(file.getFilename()) && !leaf.replace("\"", "%22").equals(file.getFilename())) {
                        throw bad("File manifest does not match the uploaded filenames");
                    }
                    sources.add(new AttachmentStorage.Source(relative, file.getSize(), file::getInputStream));
                }
                files.upload(path, fields.get("content"), fields.get("password"), version, sources);
                return HttpResponse.seeOther(URI.create(PagePaths.localUrl(PagePaths.editor(path))));
            } catch (HttpStatusException e) {
                return error(request, path, fields, version, e);
            } catch (IOException | RuntimeException e) {
                LOG.error("Unable to finish file upload", e);
                return error(request, path, fields, version, new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Unable to store files. Your draft is preserved below."));
            }
        } finally {
            // Also clean up completed parts after invalid passwords, malformed manifests and interrupted bodies.
            for (var part : parts) {
                try { part.close(); }
                catch (IOException e) { LOG.warn("Unable to clean multipart temporary file", e); }
            }
        }
    }

    @Post(value = "/delete", consumes = MediaType.APPLICATION_FORM_URLENCODED, produces = MediaType.TEXT_HTML)
    @View("page")
    public HttpResponse<?> delete(HttpRequest<?> request, @Body Map<String, String> fields) throws IOException {
        String path = pagePath(fields.get("page"));
        csrf.verify(request, fields.get("csrf"));
        long version = version(fields);
        try {
            UUID id = id(fields.get("fileId"));
            Page current = files.delete(path, id, fields.get("password"), version);
            return views.editor(request, new Page(path, fields.getOrDefault("content", ""), current.passwordHash(), current.version()),
                    null, HttpStatus.OK);
        } catch (HttpStatusException e) {
            return error(request, path, fields, version, e);
        } catch (IOException | RuntimeException e) {
            LOG.error("Unable to delete file", e);
            return error(request, path, fields, version, new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to delete the file. Your draft is preserved below."));
        }
    }

    // Include the navigation Accept type so the HTML catch-all page route cannot win negotiation.
    @Get(value = "/{fileId}", produces = {MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_HTML})
    public HttpResponse<StreamedFile> download(String fileId, HttpRequest<?> request) throws IOException {
        var download = files.download(id(fileId), request.getHeaders().getAll("If-None-Match"));
        var response = download.stream() == null ? HttpResponse.<StreamedFile>status(HttpStatus.NOT_MODIFIED)
                : HttpResponse.ok(download.stream()).contentType(MediaType.APPLICATION_OCTET_STREAM_TYPE);
        // Never serve uploaded HTML/SVG/JS as active same-origin content, including cached downloads.
        return response.header("Cache-Control", BrowserCache.CONTROL).header("ETag", download.etag())
                .header("Content-Security-Policy", "sandbox allow-downloads; default-src 'none'; frame-ancestors 'none'");
    }

    private HttpResponse<?> error(HttpRequest<?> request, String path, Map<String, String> fields,
                                  long version, HttpStatusException error) {
        return views.editor(request, new Page(path, fields.getOrDefault("content", ""), null, version),
                error.getMessage(), error.getStatus());
    }

    private static String pagePath(String value) {
        if (value == null || !value.startsWith("/")) throw bad("Missing page path");
        // Prefix an origin so leading // remains a wiki path rather than a URI authority.
        URI uri;
        try { uri = URI.create("http://wiki.invalid" + value); }
        catch (IllegalArgumentException e) { throw bad("Invalid page path"); }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) throw bad("Invalid page path");
        String path = PagePaths.canonical(uri);
        if (PagePaths.isEditor(path)) throw bad("Reserved page path");
        return path;
    }

    private static long version(Map<String, String> fields) {
        try { return Long.parseLong(fields.getOrDefault("version", "")); }
        catch (NumberFormatException e) { throw bad("Missing page version"); }
    }

    private static UUID id(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException | NullPointerException e) { throw bad("Invalid file identifier"); }
    }

    private static HttpStatusException bad(String message) { return new HttpStatusException(HttpStatus.BAD_REQUEST, message); }
}
