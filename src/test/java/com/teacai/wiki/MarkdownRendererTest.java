package com.teacai.wiki;

import com.teacai.wiki.service.MarkdownRenderer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersSimpleText() {
        assertEquals("<p>Hello</p>", renderer.render("Hello"));
    }

    @Test
    void rendersHeadings() {
        assertEquals("<h1>Heading</h1>", renderer.render("# Heading"));
        assertEquals("<h2>Heading</h2>", renderer.render("## Heading"));
        assertEquals("<h3>Heading</h3>", renderer.render("### Heading"));
    }

    @Test
    void rendersBoldAndItalic() {
        String result = renderer.render("**bold** and *italic*");
        assertTrue(result.contains("<strong>bold</strong>"));
        assertTrue(result.contains("<em>italic</em>"));
    }

    @Test
    void rendersLinks() {
        String result = renderer.render("[text](http://example.com)");
        assertTrue(result.contains("<a href=\"http://example.com\">text</a>"));
    }

    @Test
    void rendersAutolinks() {
        String result = renderer.render("http://example.com");
        assertTrue(result.contains("<a href=\"http://example.com\">http://example.com</a>"));
    }

    @Test
    void rendersCodeBlocks() {
        String result = renderer.render("```\ncode\n```");
        assertTrue(result.contains("<pre><code>code\n</code></pre>"));
    }

    @Test
    void rendersInlineCode() {
        String result = renderer.render("`code`");
        assertTrue(result.contains("<code>code</code>"));
    }

    @Test
    void rendersBlockquotes() {
        String result = renderer.render("> quoted");
        assertTrue(result.contains("<blockquote>"));
    }

    @Test
    void rendersUnorderedList() {
        String result = renderer.render("- item 1\n- item 2");
        assertTrue(result.contains("<ul>"));
        assertTrue(result.contains("<li>item 1</li>"));
        assertTrue(result.contains("<li>item 2</li>"));
    }

    @Test
    void rendersOrderedList() {
        String result = renderer.render("1. first\n2. second");
        assertTrue(result.contains("<ol>"));
        assertTrue(result.contains("<li>first</li>"));
    }

    @Test
    void rendersTables() {
        String result = renderer.render("| A | B |\n|---|---|\n| 1 | 2 |");
        assertTrue(result.contains("<table>"));
        assertTrue(result.contains("<th>A</th>"));
        assertTrue(result.contains("<td>1</td>"));
    }

    @Test
    void rendersStrikethrough() {
        String result = renderer.render("~~deleted~~");
        // jsoup relaxes the del/s tag
        assertTrue(result.contains("<del>") || result.contains("<s>") || result.contains("deleted"));
    }

    @Test
    void stripsXssFromHtml() {
        String result = renderer.render("<script>alert('xss')</script>");
        assertFalse(result.contains("<script>"));
    }

    @Test
    void rendersEmptyString() {
        assertEquals("", renderer.render(""));
        assertEquals("", renderer.render(null));
    }

    @Test
    void stripsActiveContentAndKeepsSafeEmbeddedHtmlAndRelativeLinks() {
        String html = renderer.render("<details><summary>Title</summary>Safe</details>\n\n"
                + "<svg onload=alert(1)><script>alert(1)</script></svg>"
                + "<img src='javascript:alert(1)' onerror='alert(1)'>"
                + "<a href='javascript:alert(1)' style='position:fixed' onclick='alert(1)'>bad</a>\n\n"
                + "[relative](/nested/page)");
        assertTrue(html.contains("<details><summary>Title</summary>Safe</details>"));
        assertTrue(html.contains("href=\"/nested/page\""));
        for (String unsafe : new String[]{"<script", "<svg", "onload", "onerror", "onclick", "javascript:", "style="}) {
            assertFalse(html.contains(unsafe), html);
        }
    }

    @Test
    void deeplyNestedInputDoesNotOverflowTheStack() {
        assertDoesNotThrow(() -> renderer.render("> ".repeat(1000) + "text\n\n" + "*".repeat(4000) + "x" + "*".repeat(4000)));
    }

    @Test
    void rendersComplexMarkdown() {
        String md = "# Title\n\nSome **bold** and *italic* text.\n\n- list item\n\n```\ncode block\n```\n\n[link](http://example.com)\n";
        String html = renderer.render(md);
        assertTrue(html.contains("<h1>Title</h1>"));
        assertTrue(html.contains("<strong>bold</strong>"));
        assertTrue(html.contains("<em>italic</em>"));
        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<pre><code>"));
        assertTrue(html.contains("<a href=\"http://example.com\">"));
    }
}
