package com.teacai.wiki.controller;

import com.teacai.wiki.service.AdminAuth;
import com.teacai.wiki.service.BackupService;
import com.teacai.wiki.service.RestoreService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;

@Controller("/_backup")
@ExecuteOn(TaskExecutors.BLOCKING)
public class BackupController {
    private final BackupService backups;
    private final RestoreService restores;
    private final AdminAuth auth;

    public BackupController(BackupService backups, RestoreService restores, AdminAuth auth) {
        this.backups = backups;
        this.restores = restores;
        this.auth = auth;
    }

    // Match browser navigation too, otherwise the HTML wiki catch-all can win negotiation.
    @Get(produces = {"application/zip", MediaType.TEXT_HTML})
    public HttpResponse<?> download(HttpRequest<?> request) throws IOException, SQLException {
        var rejection = auth.check(request);
        if (rejection != null) return rejection;
        var archive = backups.create();
        return HttpResponse.ok(new StreamedFile(archive.stream(), MediaType.of("application/zip"), -1, archive.size())
                        .attach(archive.filename()))
                .contentType(MediaType.of("application/zip"))
                .header("Content-Security-Policy", "sandbox allow-downloads; default-src 'none'; frame-ancestors 'none'");
    }

    @Post(consumes = {"application/zip", MediaType.APPLICATION_OCTET_STREAM}, produces = MediaType.TEXT_PLAIN)
    public HttpResponse<?> restore(HttpRequest<?> request, @Body InputStream archive) throws IOException, SQLException {
        var rejection = auth.check(request);
        if (rejection != null) return rejection;
        // Only this exact path is exempt from the shared request lease in MaintenanceFilter.
        if (!request.getPath().equals("/_backup")) return HttpResponse.status(HttpStatus.BAD_REQUEST);
        try (archive) { restores.restore(archive); }
        return HttpResponse.ok("Backup restored successfully.\n");
    }

    @Post(consumes = MediaType.MULTIPART_FORM_DATA, produces = MediaType.TEXT_PLAIN)
    public HttpResponse<?> restoreMultipart(HttpRequest<?> request, @Part("file") CompletedFileUpload file) throws IOException, SQLException {
        try (file) {
            return restore(request, file.getInputStream());
        }
    }
}
