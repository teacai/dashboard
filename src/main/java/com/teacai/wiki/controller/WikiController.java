package com.teacai.wiki.controller;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.model.Page;
import com.teacai.wiki.service.CsrfService;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.MarkdownRenderer;
import com.teacai.wiki.service.PageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.views.View;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class WikiController {
    private final PageService pages;
    private final MarkdownRenderer markdown;
    private final WikiProperties properties;
    private final CsrfService csrf;
    private final MeterRegistry metrics;
    private final FileService files;

    public WikiController(PageService pages, MarkdownRenderer markdown, WikiProperties properties,
                          CsrfService csrf, MeterRegistry metrics, FileService files) {
        this.pages = pages;
        this.markdown = markdown;
        this.properties = properties;
        this.csrf = csrf;
        this.metrics = metrics;
        this.files = files;
    }

    @Get(uris = {"/", "/{+path}"}, produces = MediaType.TEXT_HTML)
    @View("page")
    public HttpResponse<?> get(HttpRequest<?> request) {
        String path = PagePaths.canonical(request.getUri());
        boolean editor = PagePaths.isEditor(path);
        if (editor) path = PagePaths.page(path);
        if (PagePaths.isEditor(path)) throw new HttpStatusException(HttpStatus.NOT_FOUND, "Reserved editor path");
        Page page = pages.getPage(path);
        if (editor) return editor(request, page, null, HttpStatus.OK);
        Map<String, Object> model = model(page, false);
        model.put("html", metrics.timer("wiki.markdown.render").record(() -> markdown.render(page.content())));
        return HttpResponse.ok(model);
    }

    @Post(uris = {"/", "/{+path}"}, consumes = MediaType.APPLICATION_FORM_URLENCODED,
            produces = MediaType.TEXT_HTML)
    @View("page")
    public HttpResponse<?> save(HttpRequest<?> request, @Body Map<String, String> form) {
        String path = PagePaths.canonical(request.getUri());
        if (PagePaths.isEditor(path)) throw new HttpStatusException(HttpStatus.NOT_FOUND, "Reserved editor path");
        csrf.verify(request, form.get("csrf"));
        long version;
        try { version = Long.parseLong(form.getOrDefault("version", "")); }
        catch (NumberFormatException e) { throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Missing page version"); }
        try {
            pages.savePage(path, form.get("content"), form.get("password"), version);
        } catch (HttpStatusException e) {
            if (e.getStatus() != HttpStatus.FORBIDDEN && e.getStatus() != HttpStatus.CONFLICT
                    && e.getStatus() != HttpStatus.TOO_MANY_REQUESTS) throw e;
            // Preserve the draft and old version. Never echo the submitted password.
            Page draft = new Page(path, form.getOrDefault("content", ""), null, version);
            return editor(request, draft, e.getMessage(), e.getStatus());
        }
        return HttpResponse.seeOther(URI.create(PagePaths.localUrl(path)));
    }

    HttpResponse<?> editor(HttpRequest<?> request, Page page, String error, HttpStatus status) {
        String token = csrf.token(request);
        var model = model(page, true);
        model.put("csrf", token);
        model.put("error", error);
        model.put("files", files.list(page.path()));
        model.put("passwordHint", pages.usesMasterPassword() ? "Master password"
                : error != null ? "Password" : page.isProtected() ? "Page password" : "Set password (optional)");
        return HttpResponse.status(status).body(model).cookie(csrf.cookie(request, token));
    }

    private Map<String, Object> model(Page page, boolean editor) {
        var model = new HashMap<String, Object>();
        model.put("appName", properties.getName());
        model.put("title", page.path().equals("/") ? properties.getName() : page.path() + " — " + properties.getName());
        model.put("path", PagePaths.localUrl(page.path()));
        model.put("pagePath", page.path());
        model.put("editorUrl", PagePaths.localUrl(PagePaths.editor(page.path())));
        model.put("editor", editor);
        model.put("content", page.content());
        model.put("version", page.version());
        return model;
    }

}
