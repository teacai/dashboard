package com.teacai.wiki;

import com.teacai.wiki.repository.PageRepository;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
class WikiHttpTest {
    @Inject EmbeddedServer server;
    @Inject PageRepository repository;
    private HttpTestSupport http;

    @BeforeEach void openClient() { http = new HttpTestSupport(server.getURI()); }
    @AfterEach void closeClient() { http.close(); }
    private String path() { return "/test/" + UUID.randomUUID(); }

    @Test void blankPagesAndRootEditor() throws Exception {
        String path = path();
        for (String url : new String[]{"/", path}) {
            var response = http.get(url);
            assertEquals(200, response.statusCode());
            var doc = Jsoup.parse(response.body());
            assertEquals("", doc.selectFirst("main").html());
            assertEquals(url.equals("/") ? "/editor" : url + "/editor", doc.selectFirst("a[aria-label='Edit page']").attr("href"));
        }
        assertNotNull(http.editor("/").get("version"));
        assertTrue(repository.findByPath(path).isEmpty());
    }

    @Test void editorUsesSameOriginReferrerPolicyForBrowserFormPosts() throws Exception {
        // no-referrer makes Chrome submit Origin: null; Micronaut rejects it before the controller.
        for (String url : new String[]{"/editor", path() + "/editor"}) {
            var response = http.get(url);
            assertEquals(200, response.statusCode());
            assertEquals("same-origin", response.headers().firstValue("referrer-policy").orElseThrow());
        }
    }

    @Test void savesAndUpdatesRootWithPostRedirectGet() throws Exception {
        var form = http.editor("/");
        form.put("content", "# Root");
        var saved = http.post("/", form);
        assertEquals(303, saved.statusCode(), saved.body());
        assertEquals("/", saved.headers().firstValue("location").orElseThrow());
        assertTrue(http.get("/").body().contains("<h1>Root</h1>"));
        form = http.editor("/");
        form.put("content", "");
        assertEquals(303, http.post("/", form).statusCode());
        assertEquals("", repository.findByPath("/").orElseThrow().content());
    }

    @Test void preservesNestedEncodedAndTrailingSlashPaths() throws Exception {
        String base = path();
        for (String suffix : new String[]{"/nested/leaf", "/a%20b/%E6%97%A5%E6%9C%AC", "/a%2Fb/%23%3F%25", "/a+b;:@!$&'()*,=", "/trailing/", "/double//slash"}) {
            String path = base + suffix;
            var form = http.editor(path);
            form.put("content", "# Saved\n\n**bold**\n\n<details><summary>More</summary>safe</details>");
            var saved = http.post(path, form);
            assertEquals(303, saved.statusCode(), path + " " + saved.body());
            assertEquals(path, saved.headers().firstValue("location").orElseThrow());
            assertTrue(http.get(path).body().contains("<strong>bold</strong>"));
            assertTrue(http.editor(path).get("content").startsWith("# Saved"));
        }
    }

    @Test void publicPagesCanBeProtectedButNeverUnprotectedOrRekeyed() throws Exception {
        String path = path();
        var form = http.editor(path);
        form.put("content", "public");
        assertEquals(303, http.post(path, form).statusCode());
        form = http.editor(path);
        form.put("password", "secret");
        form.put("content", "protected");
        assertEquals(303, http.post(path, form).statusCode());
        String hash = repository.findByPath(path).orElseThrow().passwordHash();
        assertFalse(hash.contains("secret"));
        form = http.editor(path);
        form.put("content", "my unsaved draft </textarea><script>alert(1)</script>");
        var rejected = http.post(path, form);
        assertEquals(403, rejected.statusCode());
        var doc = Jsoup.parse(rejected.body());
        assertEquals(form.get("content"), doc.selectFirst("textarea").val());
        assertTrue(doc.select("script").isEmpty());
        assertEquals("protected", repository.findByPath(path).orElseThrow().content());
        form.put("password", "wrong");
        assertEquals(403, http.post(path, form).statusCode());
        form.put("password", "secret");
        assertEquals(303, http.post(path, form).statusCode());
        assertEquals(hash, repository.findByPath(path).orElseThrow().passwordHash());
        assertTrue(http.get(path).body().contains("my unsaved draft"));
    }

    @Test void editorPreservesLeadingNewlinesWhitespaceAndHtmlAsPlainText() throws Exception {
        String path = path();
        var form = http.editor(path);
        String content = "\n\n  # Draft 🔑\n\n</textarea><script>alert(1)</script>\n  ";
        form.put("content", content);
        assertEquals(303, http.post(path, form).statusCode());
        assertEquals(content, http.editor(path).get("content"));
        assertEquals(content, repository.findByPath(path).orElseThrow().content());
    }

    @Test void rejectsMissingOrForgedCsrf() throws Exception {
        String path = path();
        assertEquals(403, http.post(path, Map.of("content", "evil", "version", "-1")).statusCode());
        var form = http.editor(path);
        form.put("csrf", form.get("csrf") + "tamper");
        assertEquals(403, http.post(path, form).statusCode());
        assertTrue(repository.findByPath(path).isEmpty());
    }

    @Test void rejectsStaleUpdatesAndConcurrentCreation() throws Exception {
        String path = path();
        var first = http.editor(path);
        var stale = http.editor(path);
        first.put("content", "first");
        assertEquals(303, http.post(path, first).statusCode());
        stale.put("content", "stale");
        assertEquals(409, http.post(path, stale).statusCode());
        assertEquals("first", repository.findByPath(path).orElseThrow().content());
        stale = http.editor(path);
        first = http.editor(path);
        first.put("content", "latest");
        assertEquals(303, http.post(path, first).statusCode());
        assertEquals(409, http.post(path, stale).statusCode());
    }

    @Test void limitsUploadsBeforeBufferingAndReleasesSlotsOnDisconnect() throws Exception {
        String path = path();
        String partial = "POST " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 1024\r\n\r\ncontent=x";
        try (var first = new Socket(server.getHost(), server.getPort());
             var second = new Socket(server.getHost(), server.getPort())) {
            first.getOutputStream().write(partial.getBytes(StandardCharsets.US_ASCII));
            second.getOutputStream().write(partial.getBytes(StandardCharsets.US_ASCII));
            int status = 0;
            for (int i = 0; i < 100 && status != 503; i++) {
                status = http.get(path).statusCode();
                if (status != 503) Thread.sleep(10);
            }
            assertEquals(503, status);
        }
        int status = 0;
        for (int i = 0; i < 100 && status != 200; i++) {
            status = http.get(path).statusCode();
            if (status != 200) Thread.sleep(10);
        }
        assertEquals(200, status);
    }

    @Test void acceptsFormsAboveFrameworkDefaultBufferLimits() throws Exception {
        String path = path();
        var form = http.editor(path);
        String content = "x".repeat(11 * 1024 * 1024);
        form.put("content", content);
        var response = http.post(path, form);
        assertEquals(303, response.statusCode(), response.body());
        assertEquals(content, repository.findByPath(path).orElseThrow().content());
    }

    @Test void leadingDoubleSlashNeverCreatesAnExternalLinkOrRedirect() throws Exception {
        String path = "//external.example/" + UUID.randomUUID();
        var response = http.get(path);
        var doc = Jsoup.parse(response.body());
        assertEquals("/." + path + "/editor", doc.selectFirst("a[aria-label='Edit page']").attr("href"));
        var form = http.editor(path);
        form.put("content", "local");
        response = http.post(path, form);
        assertEquals(303, response.statusCode(), response.body());
        assertEquals("/." + path, response.headers().firstValue("location").orElseThrow());
        assertEquals("local", repository.findByPath(path).orElseThrow().content());
    }

    @Test void servesStylesAndSecurityHeadersAndSanitizesHtml() throws Exception {
        assertEquals(200, http.get("/_assets/style.css").statusCode());
        assertTrue(http.get("/_assets/style.css").headers().firstValue("content-type").orElseThrow().startsWith("text/css"));
        String path = path();
        var form = http.editor(path);
        form.put("content", "<script>alert(1)</script><img src=x onerror=alert(1)><form action='/evil'><input name=password></form>\n\n[sneaky](javascript:alert(1))");
        assertEquals(303, http.post(path, form).statusCode());
        var response = http.get(path);
        var doc = Jsoup.parse(response.body());
        assertTrue(doc.select("script, iframe, input, form, [onerror]").isEmpty());
        assertFalse(doc.selectFirst("main").html().contains("javascript:"));
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
        assertTrue(response.headers().firstValue("content-security-policy").orElseThrow().contains("form-action 'self'"));
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        assertEquals(404, http.get("/_assets/unknown").statusCode());
        assertEquals(404, http.post(path + "/editor", form).statusCode());
    }
}
