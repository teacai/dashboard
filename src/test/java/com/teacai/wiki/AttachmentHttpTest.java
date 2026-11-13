package com.teacai.wiki;

import com.teacai.wiki.config.WikiProperties;
import com.teacai.wiki.repository.FileRepository;
import com.teacai.wiki.repository.PageRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@Property(name = "wiki.max-upload-size", value = "1024")
@Property(name = "micronaut.server.multipart.max-file-size", value = "1024")
class AttachmentHttpTest {
    @Inject EmbeddedServer server;
    @Inject WikiProperties properties;
    @Inject FileRepository files;
    @Inject PageRepository pages;
    @Inject JsonMapper json;
    private HttpTestSupport http;

    @BeforeEach void open() { http = new HttpTestSupport(server.getURI()); }
    @AfterEach void close() { http.close(); }
    private String page() { return "/attachments/" + UUID.randomUUID(); }
    private Path stored(String path) { return Path.of(properties.getFilesDirectory()).resolve(path.substring(1)); }

    private Map<String, String> form(String page, String... relativePaths) throws Exception {
        var fields = http.editor(page);
        fields.put("page", page);
        fields.put("manifest", json.writeValueAsString(relativePaths));
        return fields;
    }
    private HttpTestSupport.UploadPart part(int index, String filename, String text) {
        return new HttpTestSupport.UploadPart("file" + index, filename, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void uploadsNestedDirectoriesSavesDraftAndAppendsLinksThenListsAndDownloads() throws Exception {
        String page = page() + "/nested";
        var fields = form(page, "report.txt", "photos/trip/image & notes.txt", "photos/empty.bin");
        fields.put("content", "# Unsaved draft\n\nOriginal text");
        // Deliberately reverse multipart completion order: indexed names must match the manifest.
        var response = http.upload(fields, List.of(part(2, "empty.bin", ""), part(1, "image & notes.txt", "picture"), part(0, "report.txt", "report")));
        assertEquals(303, response.statusCode(), response.body());
        assertEquals(page + "/editor", response.headers().firstValue("location").orElseThrow());
        assertEquals("report", Files.readString(stored(page + "/report.txt")));
        assertEquals("picture", Files.readString(stored(page + "/photos/trip/image & notes.txt")));
        assertEquals(0, Files.size(stored(page + "/photos/empty.bin")));
        var uploaded = files.list(page);
        assertEquals(3, uploaded.size());
        String content = pages.findByPath(page).orElseThrow().content();
        assertTrue(content.startsWith(fields.get("content") + "\n\n- [report"));
        assertEquals(3, Jsoup.parse(http.get(page).body()).select("main li a[href^=/_files/]").size());
        for (var file : uploaded) {
            assertTrue(content.contains("](" + file.url() + ")"));
            var download = http.get(file.url(), "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            assertEquals(200, download.statusCode());
            assertTrue(download.headers().firstValue("content-type").orElseThrow().startsWith("application/octet-stream"));
            assertTrue(download.headers().firstValue("content-disposition").orElseThrow().startsWith("attachment;"));
            assertTrue(download.headers().firstValue("content-security-policy").orElseThrow().contains("sandbox"));
        }
        var editor = Jsoup.parse(http.get(page + "/editor").body());
        assertEquals(3, editor.select(".file-list li").size());
        for (var row : editor.select(".file-list li")) {
            var insert = row.selectFirst("button.insert-file");
            assertNotNull(insert);
            assertEquals("button", insert.attr("type"));
            assertEquals("Insert " + row.selectFirst("a").text(), insert.attr("aria-label"));
            assertEquals("Insert file link", insert.attr("title"));
            assertTrue(insert.text().isEmpty());
            assertNotNull(insert.selectFirst("svg[aria-hidden=true]"));
            assertEquals("button", insert.nextElementSibling().tagName());
            assertTrue(insert.nextElementSibling().hasClass("delete-file"));
        }
        assertEquals(content, editor.selectFirst("textarea").wholeText());
    }

    @Test void replacesFilesWithoutChangingExistingLinks() throws Exception {
        String page = page();
        assertEquals(303, http.upload(form(page, "original.txt"), List.of(part(0, "original.txt", "old"))).statusCode());
        var original = files.list(page).getFirst();
        var fields = form(page, "original.txt");
        fields.put("content", "Current edited draft");
        assertEquals(303, http.upload(fields, List.of(part(0, "original.txt", "replacement"))).statusCode());
        assertEquals(1, files.list(page).size());
        assertEquals(original.publicId(), files.list(page).getFirst().publicId());
        assertEquals("replacement", http.get(original.url()).body());
        assertEquals("replacement", Files.readString(stored(page + "/original.txt")));
        assertTrue(pages.findByPath(page).orElseThrow().content().startsWith("Current edited draft\n\n- "));
    }

    @Test void requiresPasswordAndCsrfAndPreservesDraftWithoutWritingFiles() throws Exception {
        String page = page();
        var fields = http.editor(page);
        fields.put("content", "protected");
        fields.put("password", "secret");
        assertEquals(303, http.post(page, fields).statusCode());
        fields = form(page, "private.txt");
        fields.put("content", "Unsaved </textarea> draft");
        assertEquals(403, http.upload(fields, List.of(part(0, "private.txt", "bad"))).statusCode());
        assertTrue(files.list(page).isEmpty());
        assertFalse(Files.exists(stored(page + "/private.txt")));
        fields.put("password", "secret");
        String token = fields.put("csrf", "forged");
        assertEquals(403, http.upload(fields, List.of(part(0, "private.txt", "bad"))).statusCode());
        fields.put("csrf", token);
        assertEquals(303, http.upload(fields, List.of(part(0, "private.txt", "good"))).statusCode());
        assertTrue(pages.findByPath(page).orElseThrow().content().startsWith("Unsaved </textarea> draft"));
    }

    @Test void deletionAuthenticatesOwnerPreservesUnsavedDraftAndDoesNotRewriteLinks() throws Exception {
        String page = page();
        var upload = form(page, "file.txt");
        upload.put("password", "secret");
        assertEquals(303, http.upload(upload, List.of(part(0, "file.txt", "data"))).statusCode());
        var file = files.list(page).getFirst();
        String saved = pages.findByPath(page).orElseThrow().content();
        var deletion = http.editor(page);
        deletion.put("page", page);
        deletion.put("fileId", file.publicId().toString());
        deletion.put("content", "Unsaved deletion draft");
        assertEquals(403, http.post("/_files/delete", deletion).statusCode());
        assertEquals(200, http.get(file.url()).statusCode());
        deletion.put("password", "secret");
        var response = http.post("/_files/delete", deletion);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("Unsaved deletion draft", Jsoup.parse(response.body()).selectFirst("textarea").wholeText());
        assertEquals(saved, pages.findByPath(page).orElseThrow().content());
        assertFalse(Files.exists(stored(page + "/file.txt")));
        assertTrue(files.list(page).isEmpty());
        assertEquals(404, http.get(file.url()).statusCode());
    }

    @Test void rejectsStaleUploadAndDeleteWithoutReplacingExistingFiles() throws Exception {
        String page = page();
        assertEquals(303, http.upload(form(page, "file.txt"), List.of(part(0, "file.txt", "old"))).statusCode());
        var stale = form(page, "file.txt");
        var current = http.editor(page);
        current.put("content", "new revision");
        assertEquals(303, http.post(page, current).statusCode());
        assertEquals(409, http.upload(stale, List.of(part(0, "file.txt", "evil replacement"))).statusCode());
        var file = files.list(page).getFirst();
        stale.remove("manifest");
        stale.put("fileId", file.publicId().toString());
        assertEquals(409, http.post("/_files/delete", stale).statusCode());
        assertEquals("old", Files.readString(stored(page + "/file.txt")));
        assertEquals("new revision", pages.findByPath(page).orElseThrow().content());
    }

    @Test void parentAndChildPagesCannotOverwriteOrDeleteEachOthersFiles() throws Exception {
        String parent = page();
        String child = parent + "/folder";
        assertEquals(303, http.upload(form(child, "file.txt"), List.of(part(0, "file.txt", "owned by child"))).statusCode());
        var collision = form(parent, "folder/file.txt");
        assertEquals(409, http.upload(collision, List.of(part(0, "file.txt", "overwrite"))).statusCode());
        assertTrue(pages.findByPath(parent).isEmpty());
        assertEquals("owned by child", Files.readString(stored(child + "/file.txt")));
        var deletion = http.editor(parent);
        deletion.put("page", parent);
        deletion.put("fileId", files.list(child).getFirst().publicId().toString());
        assertEquals(404, http.post("/_files/delete", deletion).statusCode());
        assertEquals(1, files.list(child).size());
    }

    @Test void rejectsTraversalDuplicatePathsAndInvalidManifests() throws Exception {
        String page = page();
        for (String path : List.of("../escape.txt", "/absolute.txt", "folder/../../escape.txt", "folder\\escape.txt", "C:/escape.txt", "a//escape.txt", "a/./escape.txt")) {
            var fields = form(page, path);
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            assertEquals(400, http.upload(fields, List.of(part(0, leaf, "bad"))).statusCode(), path);
        }
        assertEquals(409, http.upload(form(page, "dup.txt", "dup.txt"), List.of(part(0, "dup.txt", "a"), part(1, "dup.txt", "b"))).statusCode());
        assertEquals(400, http.upload(form(page, "wrong.txt"), List.of(part(0, "actual.txt", "bad"))).statusCode());
        var invalid = form(page, "file.txt");
        invalid.put("manifest", "not json");
        assertEquals(400, http.upload(invalid, List.of(part(0, "file.txt", "bad"))).statusCode());
        assertTrue(files.list(page).isEmpty());
        assertTrue(pages.findByPath(page).isEmpty());
    }

    @Test void enforcesExactPerFileAndAggregateLimitsUsingDiskBackedMultipart() throws Exception {
        String page = page();
        assertEquals(303, http.upload(form(page, "exact.bin"), List.of(part(0, "exact.bin", "x".repeat(1024)))).statusCode());
        assertEquals(413, http.upload(form(page, "large.bin"), List.of(part(0, "large.bin", "x".repeat(1025)))).statusCode());
        assertEquals(413, http.upload(form(page, "one.bin", "two.bin"), List.of(part(0, "one.bin", "x".repeat(600)), part(1, "two.bin", "y".repeat(425)))).statusCode());
        assertEquals(1, files.list(page).size());
        assertFalse(Files.exists(stored(page + "/one.bin")));
        assertFalse(Files.exists(stored(page + "/two.bin")));
        assertEquals(303, http.upload(form(page, "one.bin", "two.bin"), List.of(part(0, "one.bin", "x".repeat(600)), part(1, "two.bin", "y".repeat(424)))).statusCode());
        assertEquals(3, files.list(page).size());
    }

    @Test void preservesEncodedAndEmptyPageSegmentsAndServesUploadedHtmlAsDownload() throws Exception {
        String base = page();
        for (String suffix : List.of("/a%2Fb", "/trailing/", "/repeated//leaf", "/a+b;:@!$&'()*,=")) {
            String page = base + suffix;
            assertEquals(303, http.upload(form(page, "unsafe.html"), List.of(part(0, "unsafe.html", "<script>alert(1)</script>"))).statusCode());
            var download = http.get(files.list(page).getFirst().url());
            assertEquals("<script>alert(1)</script>", download.body());
            assertEquals("application/octet-stream", download.headers().firstValue("content-type").orElseThrow());
            assertTrue(download.headers().firstValue("content-disposition").orElseThrow().contains("unsafe.html"));
        }
    }
}
