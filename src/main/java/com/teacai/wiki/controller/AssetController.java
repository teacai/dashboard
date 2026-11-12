package com.teacai.wiki.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.server.types.files.StreamedFile;

@Controller("/_assets")
public class AssetController {
    @Get(value = "/editor.js", produces = "text/javascript")
    public HttpResponse<StreamedFile> editorScript() {
        return HttpResponse.ok(new StreamedFile(AssetController.class.getResource("/static/js/editor.js")));
    }

    @Get(value = "/style.css", produces = "text/css")
    public HttpResponse<StreamedFile> stylesheet() {
        return HttpResponse.ok(new StreamedFile(AssetController.class.getResource("/static/css/style.css")));
    }
}
