package com.teacai.wiki;

import com.teacai.wiki.service.MarkdownRenderer;
import com.teacai.wiki.service.PasswordService;
import com.teacai.wiki.service.PageService;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import com.teacai.wiki.config.WikiProperties;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
class WikiServiceTest {

    @Inject
    ApplicationContext context;

    @Inject
    MarkdownRenderer markdownRenderer;

    @Inject
    PasswordService passwordService;

    @Inject
    WikiProperties wikiProperties;

    @Inject PageService pages;
    @Inject MeterRegistry metrics;

    @Test
    void exposesDatabaseMetricsToLocalJmx() throws Exception {
        pages.getPage("/metrics-test");
        assertTrue(metrics.get("wiki.database.read").timer().count() > 0);
        assertFalse(ManagementFactory.getPlatformMBeanServer()
                .queryNames(new ObjectName("metrics:*"), null).isEmpty());
    }

    @Test
    void rendersMarkdownCorrectly() {
        String result = markdownRenderer.render("# Hello\n\n**bold** and *italic*");
        assertNotNull(result);
        assertTrue(result.contains("<h1>Hello</h1>"));
        assertTrue(result.contains("<strong>bold</strong>"));
        assertTrue(result.contains("<em>italic</em>"));
    }

    @Test
    void sanitizesXss() {
        String result = markdownRenderer.render("<script>alert('xss')</script>\n\n<iframe src='evil'></iframe>");
        assertFalse(result.contains("<script>"));
        assertFalse(result.contains("<iframe"));
    }

    @Test
    void hashesAndVerifiesPasswords() {
        String hash = passwordService.hashPassword("secure-password-123");
        assertNotNull(hash);
        assertTrue(passwordService.verifyPassword("secure-password-123", hash));
        assertFalse(passwordService.verifyPassword("wrong-password", hash));
    }

    @Test
    void defaultMaxPageSizeIs100MB() {
        assertEquals(104857600, wikiProperties.getMaxPageSize());
    }

    @Test
    void rendersTablesAndLists() {
        String md = "| A | B |\n|---|---|\n| 1 | 2 |";
        String result = markdownRenderer.render(md);
        assertTrue(result.contains("<table>"));

        md = "- item1\n- item2\n- item3";
        result = markdownRenderer.render(md);
        assertTrue(result.contains("<ul>"));
        assertTrue(result.contains("<li>item1</li>"));
    }

    @Test
    void emptyInputReturnsEmptyString() {
        assertEquals("", markdownRenderer.render(null));
        assertEquals("", markdownRenderer.render(""));
        assertEquals("", markdownRenderer.render("   "));
    }
}
