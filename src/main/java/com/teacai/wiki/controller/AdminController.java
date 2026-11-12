package com.teacai.wiki.controller;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.service.AdminAuth;
import com.teacai.wiki.service.CsrfService;
import com.teacai.wiki.service.RestoreService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.views.ModelAndView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.HashMap;

@Controller("/_admin")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AdminController {
    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);
    private final AdminAuth auth;
    private final CsrfService csrf;
    private final RestoreService restores;
    private final WikiProperties properties;

    public AdminController(AdminAuth auth, CsrfService csrf, RestoreService restores, WikiProperties properties) {
        this.auth = auth;
        this.csrf = csrf;
        this.restores = restores;
        this.properties = properties;
    }

    @Get(produces = MediaType.TEXT_HTML)
    public HttpResponse<?> index(HttpRequest<?> request, @QueryValue(defaultValue = "false") boolean restored) {
        var rejection = auth.checkCredentials(request);
        if (rejection != null) return rejection;
        return page(request, HttpStatus.OK, null, restored);
    }

    @Post(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA, produces = MediaType.TEXT_HTML)
    public HttpResponse<?> restore(HttpRequest<?> request, @Part("file") CompletedFileUpload file,
                                   @Nullable @Part("csrf") String token, @Nullable @Part("confirm") String confirmation) {
        try (file) {
            var rejection = auth.checkCredentials(request);
            if (rejection != null) return rejection;
            // This exact route is exempt from the shared maintenance lease, as the service needs exclusivity.
            if (!request.getPath().equals("/_admin/restore")) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Use the restore form at /_admin.");
            }
            try { csrf.verify(request, token); }
            catch (HttpStatusException e) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "Invalid or expired form token. Reload the admin page and try again.");
            }
            if (!"restore".equals(confirmation)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Confirm that restoring will replace the current database and all files.");
            }
            try (var input = file.getInputStream()) { restores.restore(input); }
            // Post/Redirect/Get avoids repeating the destructive operation on browser refresh.
            return HttpResponse.seeOther(URI.create("/_admin?restored=true"));
        } catch (HttpStatusException e) {
            return page(request, e.getStatus(), e.getMessage(), false);
        } catch (IOException | SQLException | RuntimeException e) {
            LOG.error("Administrator restore request failed", e);
            return page(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Restore failed. Check the server logs for rollback or recovery details before retrying.", false);
        }
    }

    private HttpResponse<?> page(HttpRequest<?> request, HttpStatus status, String error, boolean restored) {
        String token = csrf.token(request);
        var model = new HashMap<String, Object>();
        model.put("appName", properties.getName());
        model.put("title", "Administration — " + properties.getName());
        model.put("csrf", token);
        model.put("error", error);
        model.put("restored", restored);
        var response = HttpResponse.status(status).body(new ModelAndView<>("admin", model))
                .contentType(MediaType.TEXT_HTML_TYPE).cookie(csrf.cookie(request, token));
        if (status == HttpStatus.SERVICE_UNAVAILABLE) response.header("Retry-After", "1");
        return response;
    }
}
