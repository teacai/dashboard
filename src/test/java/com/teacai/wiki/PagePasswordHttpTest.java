package com.teacai.wiki;

import com.teacai.wiki.repository.PageRepository;
import com.teacai.wiki.service.PasswordService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PagePasswordHttpTest {
    private EmbeddedServer server;
    private HttpTestSupport http;
    private PageRepository repository;
    private PasswordService passwords;
    private final String path = "/password/" + UUID.randomUUID();

    @BeforeEach void start() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "datasources.default.url", "jdbc:h2:mem:password_" + UUID.randomUUID(),
                "wiki.max-page-size", 256), "test");
        http = new HttpTestSupport(server.getURI());
        repository = server.getApplicationContext().getBean(PageRepository.class);
        passwords = server.getApplicationContext().getBean(PasswordService.class);
    }

    @AfterEach void stop() { http.close(); server.close(); }

    private Map<String, String> changeForm(String oldPassword, String newPassword) throws Exception {
        var form = http.editor(path);
        form.put("action", "change-password");
        form.put("oldPassword", oldPassword);
        form.put("newPassword", newPassword);
        form.put("confirmPassword", newPassword);
        return form;
    }

    private void protect() throws Exception {
        var form = http.editor(path);
        form.put("password", "old-secret");
        form.put("content", "original");
        assertEquals(303, http.post(path, form).statusCode());
    }

    @Test void pageSettingsContainsFilesUploadsAndASeparatePasswordFormWithoutLeakingFieldsIntoUploads() throws Exception {
        var doc = Jsoup.parse(http.get(path + "/editor").body());
        assertNotNull(doc.selectFirst("button[aria-label='Page settings']"));
        assertTrue(doc.select(".upload-button, #upload-menu, #manage-files").isEmpty());
        var settings = doc.selectFirst("dialog#settings-dialog");
        assertEquals(2, settings.select("[role=tablist] [role=tab]").size());
        var filesTab = settings.selectFirst("#settings-files-tab");
        var passwordTab = settings.selectFirst("#settings-password-tab");
        assertEquals("true", filesTab.attr("aria-selected"));
        assertEquals("0", filesTab.attr("tabindex"));
        assertEquals("false", passwordTab.attr("aria-selected"));
        assertEquals("-1", passwordTab.attr("tabindex"));
        assertFalse(settings.selectFirst("#" + filesTab.attr("aria-controls")).hasAttr("hidden"));
        assertTrue(settings.selectFirst("#" + passwordTab.attr("aria-controls")).hasAttr("hidden"));
        assertNotNull(settings.selectFirst("#settings-files-panel #choose-files"));
        assertNotNull(settings.selectFirst("#choose-folder"));
        assertEquals(3, settings.select("#password-settings input[type=password]").size());
        assertTrue(doc.select("#editor input[name=newPassword], input[form=editor][name=newPassword]").isEmpty());
        assertEquals(path, settings.selectFirst("form").attr("action"));
    }

    @Test void setsProtectionAndSavesDraftOnANewPageWithoutUsingTheNavbarPassword() throws Exception {
        var form = changeForm("", "new-secret");
        form.put("password", "ignored-navbar-secret");
        form.put("content", "# New draft 🔑");
        var response = http.post(path, form);
        assertEquals(200, response.statusCode(), response.body());
        var page = repository.findByPath(path).orElseThrow();
        assertEquals(form.get("content"), page.content());
        assertEquals(0, page.version());
        assertTrue(passwords.verifyPassword("new-secret", page.passwordHash()));
        assertFalse(passwords.verifyPassword("ignored-navbar-secret", page.passwordHash()));
        assertTrue(Jsoup.parse(response.body()).selectFirst("[role=status]").text().contains("draft has been saved"));
        assertFalse(response.body().contains("new-secret"));
        assertFalse(response.body().contains("ignored-navbar-secret"));
    }

    @Test void changesThePasswordAndDraftAtomicallyAndOnlyTheNewPasswordCanSaveAfterward() throws Exception {
        protect();
        var form = changeForm("old-secret", "new-secret");
        form.put("content", "Updated draft");
        assertEquals(200, http.post(path, form).statusCode());
        var page = repository.findByPath(path).orElseThrow();
        assertEquals("Updated draft", page.content());
        assertEquals(1, page.version());
        assertTrue(passwords.verifyPassword("new-secret", page.passwordHash()));
        assertFalse(passwords.verifyPassword("old-secret", page.passwordHash()));
        form = http.editor(path);
        form.put("password", "old-secret");
        assertEquals(403, http.post(path, form).statusCode());
        form.put("password", "new-secret");
        assertEquals(303, http.post(path, form).statusCode());
    }

    @Test void removesProtectionOnlyWithTheCorrectOldPasswordAndTwoEmptyNewFields() throws Exception {
        protect();
        var form = changeForm("old-secret", "");
        form.put("content", "Public draft");
        assertEquals(200, http.post(path, form).statusCode());
        var page = repository.findByPath(path).orElseThrow();
        assertNull(page.passwordHash());
        assertEquals("Public draft", page.content());
        form = http.editor(path);
        form.put("content", "Public update without password");
        assertEquals(303, http.post(path, form).statusCode());
    }

    @Test void rejectsInvalidPasswordChangesWithoutSavingAndPreservesTheDraftButNeverEchoesSecrets() throws Exception {
        protect();
        var baseline = changeForm("old-secret", "new-secret");
        baseline.put("content", "Unsaved </textarea><script>alert(1)</script>");
        var original = repository.findByPath(path).orElseThrow();
        for (var alteration : new String[][]{
                {"oldPassword", "wrong-secret", "403"},
                {"newPassword", "different-secret", "400"},
                {"confirmPassword", "", "400"},
                {"newPassword", "x".repeat(1025), "400"},
                {"confirmPassword", "x".repeat(1025), "400"},
                {"oldPassword", "x".repeat(1025), "400"},
                {"content", "x".repeat(257), "413"}}) {
            var form = new HashMap<>(baseline);
            form.put(alteration[0], alteration[1]);
            var response = http.post(path, form);
            assertEquals(Integer.parseInt(alteration[2]), response.statusCode(), response.body());
            var doc = Jsoup.parse(response.body());
            assertEquals(form.get("content"), doc.selectFirst("textarea").val());
            assertNotNull(doc.selectFirst("#settings-dialog [role=alert]"));
            assertEquals("true", doc.selectFirst("#settings-dialog").attr("data-open"));
            assertTrue(doc.select("input[type=password]").stream().allMatch(input -> input.val().isEmpty()));
            assertTrue(doc.select("script:not([src='/_assets/editor.js'])").isEmpty());
            assertEquals(original, repository.findByPath(path).orElseThrow());
            assertFalse(response.body().contains("old-secret"));
            assertFalse(response.body().contains("new-secret"));
        }
        for (String missing : new String[]{"newPassword", "confirmPassword"}) {
            var form = new HashMap<>(baseline);
            form.remove(missing);
            assertEquals(400, http.post(path, form).statusCode());
            assertEquals(original, repository.findByPath(path).orElseThrow());
        }
    }

    @Test void rejectsForgedCsrfAndStalePasswordChangesWithoutRestoringAnOldPasswordOrContent() throws Exception {
        protect();
        var stale = changeForm("old-secret", "stale-secret");
        stale.put("content", "Stale draft");
        var forged = new HashMap<>(stale);
        forged.put("csrf", "forged");
        assertEquals(403, http.post(path, forged).statusCode());
        var winner = changeForm("old-secret", "winner-secret");
        winner.put("content", "Winner draft");
        assertEquals(200, http.post(path, winner).statusCode());
        // Even knowing the new password must not allow a stale version to overwrite the winner.
        stale.put("oldPassword", "winner-secret");
        var response = http.post(path, stale);
        assertEquals(409, response.statusCode());
        assertEquals("Stale draft", Jsoup.parse(response.body()).selectFirst("textarea").val());
        var page = repository.findByPath(path).orElseThrow();
        assertEquals("Winner draft", page.content());
        assertTrue(passwords.verifyPassword("winner-secret", page.passwordHash()));
    }

    @Test void spacesAreSignificantAndOnlyAnExactlyEmptyPasswordRemovesProtection() throws Exception {
        var form = changeForm("", " ");
        assertEquals(200, http.post(path, form).statusCode());
        assertTrue(passwords.verifyPassword(" ", repository.findByPath(path).orElseThrow().passwordHash()));
        form = changeForm("", "replacement");
        assertEquals(403, http.post(path, form).statusCode());
    }
}
