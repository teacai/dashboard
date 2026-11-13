package com.teacai.wiki;

import com.teacai.wiki.repository.PageRepository;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
class MarkdownHttpTest {
    @Inject EmbeddedServer server;
    @Inject PageRepository repository;
    private HttpTestSupport http;

    @BeforeEach void openClient() { http = new HttpTestSupport(server.getURI()); }
    @AfterEach void closeClient() { http.close(); }
    private String path() { return "/markdown/" + UUID.randomUUID(); }

    @Test void downloadsExactStoredMarkdownWithUtf8TypeAndSlugFilename() throws Exception {
        String path = path() + "/my-notes";
        String content = "\n# Notes 日本 🔑\r\n\n**bold**\n\n<script>alert(1)</script>\n  ";
        var form = http.editor(path);
        form.put("content", content);
        assertEquals(303, http.post(path, form).statusCode());

        for (String accept : new String[]{"*/*", "text/markdown", "text/html,application/xhtml+xml"}) {
            var response = http.get(path + ".md?download=1", accept);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(content, response.body());
            assertEquals("text/markdown;charset=utf-8", response.headers().firstValue("content-type").orElseThrow()
                    .replace(" ", "").toLowerCase(java.util.Locale.ROOT));
            String disposition = response.headers().firstValue("content-disposition").orElseThrow();
            assertTrue(disposition.startsWith("attachment;"), disposition);
            assertTrue(disposition.contains("filename=\"my-notes.md\""), disposition);
            assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        }
        assertTrue(http.get(path).body().contains("<strong>bold</strong>"));
        assertTrue(repository.findByPath(path + ".md").isEmpty());
    }

    @Test void missingMarkdownIsEmptyWithoutCreatingAPage() throws Exception {
        String path = path();
        var response = http.get(path + ".md");
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
        assertTrue(repository.findByPath(path).isEmpty());
        assertTrue(repository.findByPath(path + ".md").isEmpty());
    }

    @Test void rootTrailingSlashAndRepeatedSuffixAliasesUseTheSamePage() throws Exception {
        var root = http.get("/.md");
        assertEquals(200, root.statusCode());
        assertEquals(http.editor("/").get("content"), root.body());
        assertTrue(root.headers().firstValue("content-disposition").orElseThrow().contains("filename=\"index.md\""));
        assertEquals("/", Jsoup.parse(http.get("/.md/editor").body()).selectFirst("form#editor").attr("action"));

        String path = path() + "/notes/";
        var form = http.editor(path + ".md");
        form.put("content", "trailing slash content");
        var response = http.post(path + ".md", form);
        assertEquals(303, response.statusCode());
        assertEquals(path, response.headers().firstValue("location").orElseThrow());
        response = http.get(path + ".md.md");
        assertEquals(form.get("content"), response.body());
        assertTrue(response.headers().firstValue("content-disposition").orElseThrow().contains("filename=\"notes.md\""));
        assertTrue(repository.findByPath(path).isPresent());
        assertTrue(repository.findByPath(path + ".md").isEmpty());
    }

    @Test void markdownEditorUsesTheHtmlPageAndRedirectsThereAfterSaving() throws Exception {
        String path = path();
        var form = http.editor(path);
        form.put("content", "# Original");
        assertEquals(303, http.post(path, form).statusCode());

        var response = http.get(path + ".md/editor", "text/html");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-disposition").isEmpty());
        var doc = Jsoup.parse(response.body());
        assertEquals(path, doc.selectFirst("form#editor").attr("action"));
        assertEquals(path, doc.selectFirst("input[name=page]").val());
        assertEquals("# Original", doc.selectFirst("textarea").val());
        form = http.editor(path + ".md");
        form.put("content", "# Updated");
        response = http.post(doc.selectFirst("form#editor").attr("action"), form);
        assertEquals(303, response.statusCode(), response.body());
        assertEquals(path, response.headers().firstValue("location").orElseThrow());
        assertTrue(http.get(path).body().contains("<h1>Updated</h1>"));
        assertEquals("# Updated", http.get(path + ".md").body());
        assertTrue(repository.findByPath(path + ".md").isEmpty());
    }

    @Test void directMarkdownPostsShareVersionChecksAndPreserveRejectedDrafts() throws Exception {
        String path = path();
        var first = http.editor(path + ".md");
        var stale = http.editor(path);
        first.put("content", "saved");
        var response = http.post(path + ".md", first);
        assertEquals(303, response.statusCode(), response.body());
        assertEquals(path, response.headers().firstValue("location").orElseThrow());

        stale.put("content", "<unsaved draft>");
        response = http.post(path + ".md", stale);
        assertEquals(409, response.statusCode());
        var doc = Jsoup.parse(response.body());
        assertEquals("<unsaved draft>", doc.selectFirst("textarea").val());
        assertEquals(path, doc.selectFirst("form#editor").attr("action"));
        assertEquals("saved", http.get(path + ".md").body());

        var forged = http.editor(path + ".md");
        forged.put("csrf", "forged");
        assertEquals(403, http.post(path + ".md", forged).statusCode());
    }

    @Test void markdownAliasesDoNotBypassPagePasswords() throws Exception {
        String path = path();
        var form = http.editor(path);
        form.put("content", "protected content");
        form.put("password", "markdown-password");
        assertEquals(303, http.post(path, form).statusCode());
        assertEquals("protected content", http.get(path + ".md").body());
        form = http.editor(path + ".md");
        form.put("content", "unauthorized change");
        var response = http.post(path + ".md", form);
        assertEquals(403, response.statusCode());
        assertEquals("unauthorized change", Jsoup.parse(response.body()).selectFirst("textarea").val());
        assertEquals("protected content", http.get(path + ".md").body());
        form.put("password", "markdown-password");
        assertEquals(303, http.post(path + ".md", form).statusCode());
    }

    @Test void encodedPathsRemainDistinctAndFilenamesCannotInjectHeaders() throws Exception {
        String base = path();
        for (String slug : new String[]{"a%2Fb", "a%20b", "%E6%97%A5", "%22%3Bfilename%3Devil", "a+b"}) {
            String path = base + "/" + slug;
            var form = http.editor(path + ".md");
            form.put("content", "content for " + slug);
            assertEquals(303, http.post(path, form).statusCode());
            var response = http.get(path + ".%6Dd");
            assertEquals(200, response.statusCode());
            assertEquals(form.get("content"), response.body());
            assertTrue(response.headers().firstValue("content-disposition").orElseThrow()
                    .contains("filename=\"" + slug + ".md\""));
            assertTrue(repository.findByPath(path).isPresent());
        }
        assertEquals("", http.get(base + "/a/b.md").body());
    }

    @Test void markdownAliasesCannotExposeReservedPathsOrEditors() throws Exception {
        for (String path : new String[]{"/_admin.md", "/_backup.md", "/_assets.md", "/_files.md", "/editor.md",
                "/notes/editor.md", "/notes/editor.md/editor", "/_admin.md/editor"}) {
            int status = http.get(path).statusCode();
            assertTrue(status == 404 || status == 401, path + ": " + status);
        }
        assertEquals(400, http.get("/...md").statusCode());
    }
}
