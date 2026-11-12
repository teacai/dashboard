package com.teacai.wiki;

import com.teacai.wiki.service.AttachmentStorage;
import com.teacai.wiki.service.BackupService;
import com.teacai.wiki.service.FileService;
import com.teacai.wiki.service.PageService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdminHttpTest {
    @TempDir Path directory;

    private Map<String, Object> config() {
        Path root = directory.resolve("data");
        return Map.of("datasources.default.url", "jdbc:h2:file:" + root.resolve("wiki"),
                "wiki.data-directory", root.toString(), "wiki.files-directory", root.resolve("files").toString(),
                "wiki.master-password", "", "wiki.admin-username", "operator", "wiki.admin-password", "admin-secret",
                "wiki.name", "Wiki <script>not markup</script>", "micronaut.server.port", -1);
    }

    private String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder authenticated(URI base, String path) {
        return HttpRequest.newBuilder(base.resolve(path)).header("Authorization", basic("operator:admin-secret"))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    }

    private HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }

    private HttpResponse<String> admin(HttpClient client, URI base) throws Exception {
        return client.send(authenticated(base, "/_admin").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> restore(HttpClient client, URI base, byte[] archive, String token,
                                         String confirmation, String fetchSite) throws Exception {
        String boundary = "admin-restore-boundary";
        var body = new ByteArrayOutputStream();
        var fields = new java.util.LinkedHashMap<String, String>();
        if (token != null) fields.put("csrf", token);
        if (confirmation != null) fields.put("confirm", confirmation);
        for (var field : fields.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"backup.zip\"\r\n"
                + "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(archive);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        return client.send(authenticated(base, "/_admin/restore").header("Origin", origin)
                .header("Referer", origin + "/_admin").header("Sec-Fetch-Site", fetchSite)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void challengesAdminPageAndRestoreAndProtectsTheNamespace() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = browser()) {
            for (String credentials : new String[]{null, "admin:admin112", "operator:wrong"}) {
                for (String path : List.of("/_admin", "/_admin/unknown", "/_admin/restore")) {
                    var builder = HttpRequest.newBuilder(server.getURI().resolve(path));
                    if (credentials != null) builder.header("Authorization", basic(credentials));
                    if (path.endsWith("/restore")) {
                        builder.header("Content-Type", "multipart/form-data; boundary=invalid")
                                .POST(HttpRequest.BodyPublishers.ofString("not an upload"));
                    }
                    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(401, response.statusCode(), path);
                    assertTrue(response.headers().firstValue("www-authenticate").orElseThrow().startsWith("Basic "));
                    assertFalse(response.body().contains("admin-restore"));
                }
            }
            assertEquals(404, client.send(authenticated(server.getURI(), "/_admin/editor").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var response = admin(client, server.getURI());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
            var page = Jsoup.parse(response.body());
            assertEquals(0, page.select("script").size());
            assertEquals("Wiki <script>not markup</script>", page.selectFirst(".brand").text());
            assertEquals("/_backup", page.selectFirst("a.admin-button").attr("href"));
            assertEquals("multipart/form-data", page.selectFirst("form").attr("enctype"));
            assertEquals("/_admin/restore", page.selectFirst("form").attr("action"));
            assertTrue(page.selectFirst("input[name=file]").hasAttr("required"));
            assertTrue(page.selectFirst("input[name=confirm]").hasAttr("required"));
            assertFalse(page.selectFirst("input[name=csrf]").val().isBlank());
        }
    }

    @Test void requiresBasicCredentialsOnEveryRequestEvenAfterSuccessfulLogin() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = browser()) {
            var loggedIn = admin(client, server.getURI());
            assertEquals(200, loggedIn.statusCode());
            assertTrue(loggedIn.headers().firstValue("set-cookie").isPresent());
            var backup = client.send(authenticated(server.getURI(), "/_backup").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, backup.statusCode());
            assertEquals("application/zip", backup.headers().firstValue("content-type").orElseThrow());
            // Reuse the same client's cookies, including the signed CSRF cookie: they are not authentication.
            for (String path : List.of("/_admin", "/_admin?restored=true", "/_admin/", "/_admin/restore", "/_%61dmin",
                    "/_backup", "/_backup/", "/_backup?download=true", "/_backup/editor", "/_%62ackup", "/_backup%2Funknown")) {
                for (String authorization : new String[]{null, "Basic malformed", "Bearer admin-secret", basic("operator:wrong")}) {
                    var builder = HttpRequest.newBuilder(server.getURI().resolve(path));
                    if (authorization != null) builder.header("Authorization", authorization);
                    if (path.endsWith("/restore")) {
                        builder.header("Content-Type", "multipart/form-data; boundary=invalid")
                                .POST(HttpRequest.BodyPublishers.ofString("not an upload"));
                    }
                    var denied = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(401, denied.statusCode(), path);
                    assertTrue(denied.headers().firstValue("www-authenticate").orElseThrow().startsWith("Basic "));
                    assertEquals("no-store", denied.headers().firstValue("cache-control").orElseThrow());
                    assertFalse(denied.body().contains("Download backup"));
                    assertFalse(denied.body().contains("admin-restore"));
                }
            }
            var head = client.send(HttpRequest.newBuilder(server.getURI().resolve("/_admin"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, head.statusCode());
            assertTrue(head.headers().firstValue("www-authenticate").orElseThrow().startsWith("Basic "));
            assertEquals(200, admin(client, server.getURI()).statusCode());
        }
    }

    @Test void browserCanDownloadAndRestoreDatabaseAndFilesWithPostRedirectGet() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = browser()) {
            var context = server.getApplicationContext();
            context.getBean(FileService.class).upload("/saved", "original", "", -1,
                    List.of(new AttachmentStorage.Source("file.txt", 3, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}))));
            var page = Jsoup.parse(admin(client, server.getURI()).body());
            var downloaded = client.send(authenticated(server.getURI(), page.selectFirst("a.admin-button").attr("href"))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, downloaded.statusCode());
            assertTrue(downloaded.headers().firstValue("content-disposition").orElseThrow().contains("backup_"));
            var pages = context.getBean(PageService.class);
            pages.savePage("/saved", "newer page", "", pages.getPage("/saved").version());
            Path attachment = directory.resolve("data/files/saved/file.txt");
            Files.write(attachment, new byte[]{9, 9, 9});
            Files.writeString(directory.resolve("data/new-file.txt"), "new file to remove");
            var result = restore(client, server.getURI(), downloaded.body(), page.selectFirst("input[name=csrf]").val(), "restore", "same-origin");
            assertEquals(303, result.statusCode(), result.body());
            assertEquals("/_admin?restored=true", result.headers().firstValue("location").orElseThrow());
            assertTrue(pages.getPage("/saved").content().startsWith("original"));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(attachment));
            assertFalse(Files.exists(directory.resolve("data/new-file.txt")));
            var redirected = client.send(authenticated(server.getURI(), result.headers().firstValue("location").orElseThrow())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, redirected.statusCode());
            assertTrue(Jsoup.parse(redirected.body()).selectFirst("[role=status]").text().contains("Backup restored successfully"));
            // Refresh only re-renders the GET; it must not repeat the restore.
            pages.savePage("/saved", "after restore", "", pages.getPage("/saved").version());
            admin(client, server.getURI());
            assertEquals("after restore", pages.getPage("/saved").content());
        }
    }

    @Test void newerBackupDatabaseVersionIsShownAsAnAdminFormError() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = browser()) {
            var context = server.getApplicationContext();
            context.getBean(PageService.class).savePage("/unchanged", "keep current content", "", -1);
            String token = Jsoup.parse(admin(client, server.getURI()).body()).selectFirst("input[name=csrf]").val();
            byte[] snapshot;
            try (var archive = context.getBean(BackupService.class).create()) { snapshot = archive.stream().readAllBytes(); }
            var entries = RestoreTest.unzip(snapshot);
            Path future = Files.createDirectories(directory.resolve("future")).resolve("wiki.mv.db");
            Files.write(future, entries.get("wiki.mv.db"));
            try (var connection = DriverManager.getConnection("jdbc:h2:file:" + future.getParent().resolve("wiki"), "sa", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE \"flyway_schema_history\" SET \"version\" = '10' WHERE \"version\" = '3'");
            }
            entries.put("wiki.mv.db", Files.readAllBytes(future));
            var response = restore(client, server.getURI(), RestoreTest.zip(entries), token, "restore", "same-origin");
            assertEquals(400, response.statusCode(), response.body());
            String error = Jsoup.parse(response.body()).selectFirst("[role=alert]").text();
            assertTrue(error.contains("newer application database version"));
            assertTrue(error.contains("Current data is unchanged"));
            assertEquals("keep current content", context.getBean(PageService.class).getPage("/unchanged").content());
        }
    }

    @Test void csrfAndConfirmationAreRequiredAndInvalidArchivesShowSafeErrors() throws Exception {
        try (var server = ApplicationContext.run(EmbeddedServer.class, config(), "test"); var client = browser(); var withoutCookie = browser()) {
            var pages = server.getApplicationContext().getBean(PageService.class);
            pages.savePage("/unchanged", "keep me", "", -1);
            String token = Jsoup.parse(admin(client, server.getURI()).body()).selectFirst("input[name=csrf]").val();
            byte[] invalidZip = {1, 2, 3};
            assertEquals(403, restore(client, server.getURI(), invalidZip, null, "restore", "same-origin").statusCode());
            assertEquals(403, restore(client, server.getURI(), invalidZip, "forged", "restore", "same-origin").statusCode());
            assertEquals(403, restore(withoutCookie, server.getURI(), invalidZip, token, "restore", "same-origin").statusCode());
            assertEquals(403, restore(client, server.getURI(), invalidZip, token, "restore", "cross-site").statusCode());
            var unconfirmed = restore(client, server.getURI(), invalidZip, token, null, "same-origin");
            assertEquals(400, unconfirmed.statusCode(), unconfirmed.body());
            assertTrue(Jsoup.parse(unconfirmed.body()).selectFirst("[role=alert]").text().contains("Confirm"));
            var invalid = restore(client, server.getURI(), invalidZip, token, "restore", "same-origin");
            assertEquals(400, invalid.statusCode(), invalid.body());
            var errorPage = Jsoup.parse(invalid.body());
            assertTrue(errorPage.selectFirst("[role=alert]").text().contains("Invalid backup"));
            assertNotNull(errorPage.selectFirst("form[action='/_admin/restore']"));
            assertEquals("keep me", pages.getPage("/unchanged").content());
        }
    }
}
