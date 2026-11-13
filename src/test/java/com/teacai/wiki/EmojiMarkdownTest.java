package com.teacai.wiki;

import com.teacai.wiki.service.MarkdownRenderer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class EmojiMarkdownTest {
    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test void rendersEveryBundledUnicodeShortcode() throws Exception {
        var mappings = new Properties();
        try (var stream = getClass().getResourceAsStream("/emoji/shortcodes.properties")) {
            assertNotNull(stream);
            mappings.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        assertEquals(853, mappings.size());
        for (String code : mappings.stringPropertyNames()) {
            String expected = mappings.getProperty(code);
            assertTrue(expected.codePoints().anyMatch(cp -> cp > 127), code);
            assertEquals("<p>" + expected + "</p>", renderer.render(":" + code + ":"), code);
        }
    }

    @Test void handlesAliasesPunctuationAndAdjacentShortcodes() {
        assertEquals("<p>😄🚀 (👍), 👎! 💩 💩 💩 💥 💥 😆 😆</p>", renderer.render(
                ":smile::rocket: (:+1:), :-1:! :hankey: :poop: :shit: :boom: :collision: :laughing: :satisfied:"));
        assertEquals("<p>🙎 🙍 👱 🐿️ ⬛ ⬜</p>", renderer.render(
                ":person_with_pouting_face: :person_frowning: :person_with_blond_hair: :squirrel: :black_square: :white_square:"));
    }

    @Test void preservesCompleteUnicodeSequencesAndExistingEmoji() {
        assertEquals("<p>❤️ ☀️ 1️⃣ #️⃣ 🇬🇧 🇬🇧 🇺🇸 🙆‍♀️ 🚀</p>", renderer.render(
                ":heart: :sunny: :one: :hash: :gb: :uk: :us: :ok_woman: 🚀"));
    }

    @Test void rendersShortcodesInsideMarkdownFormattingAndImageAltText() {
        var doc = Jsoup.parse(renderer.render("""
                # :smile:

                **:heart:** *:rocket:* ~~:thumbsup:~~

                - :tada:

                > :warning:

                | Status |
                | --- |
                | :white_check_mark: |

                [:smile:](https://example.com/:smile: ":heart:")

                ![:rocket:](https://example.com/:rocket:.png ":smile:")
                """));
        assertEquals("😄", doc.selectFirst("h1").text());
        assertEquals("❤️", doc.selectFirst("strong").text());
        assertEquals("🚀", doc.selectFirst("em").text());
        assertEquals("👍", doc.selectFirst("del").text());
        assertEquals("🎉", doc.selectFirst("li").text());
        assertEquals("⚠️", doc.selectFirst("blockquote").text());
        assertEquals("✅", doc.selectFirst("td").text());
        assertEquals("😄", doc.selectFirst("a").text());
        assertEquals("https://example.com/:smile:", doc.selectFirst("a").attr("href"));
        assertEquals(":heart:", doc.selectFirst("a").attr("title"));
        assertEquals("🚀", doc.selectFirst("img").attr("alt"));
        assertEquals("https://example.com/:rocket:.png", doc.selectFirst("img").attr("src"));
        assertEquals(":smile:", doc.selectFirst("img").attr("title"));
    }

    @Test void leavesInlineFencedAndIndentedCodeUntouched() {
        var doc = Jsoup.parse(renderer.render("""
                `:smile:` :smile:

                ```text
                :heart: :+1:
                ```

                    :rocket:
                """));
        assertEquals(":smile:", doc.select("code").get(0).wholeText());
        assertEquals(":heart: :+1:\n", doc.select("code").get(1).wholeText());
        assertEquals(":rocket:\n", doc.select("code").get(2).wholeText());
        assertEquals(":smile: 😄", doc.selectFirst("p").text());
    }

    @Test void leavesUnknownMalformedAndImageOnlyShortcodesUntouched() {
        String text = ":unknown: :SMILE: :smile : smile: :: 12:30 https://example.com";
        assertEquals(text, Jsoup.parse(renderer.render(text)).text());
        String imageOnly = ":bowtie: :neckbeard: :feelsgood: :finnadie: :goberserk: :godmode: :hurtrealbad: "
                + ":rage1: :rage2: :rage3: :rage4: :suspect: :trollface: :octocat: :shipit:";
        assertEquals("<p>" + imageOnly + "</p>", renderer.render(imageOnly));
        String malformed = ":" + "a".repeat(100_000) + ": :smile:";
        assertTrue(renderer.render(malformed).endsWith(": 😄</p>"));
    }

    @Test void stillSanitizesHtmlAndDangerousLinks() {
        String html = renderer.render(":smile: <script>alert(1)</script> [bad :heart:](javascript:alert(1))");
        var doc = Jsoup.parse(html);
        assertTrue(doc.text().contains("😄"));
        assertTrue(doc.text().contains("bad ❤️"));
        assertTrue(doc.select("script").isEmpty());
        assertFalse(doc.selectFirst("a").attr("href").startsWith("javascript:"));
        assertEquals("<p>&lt;😄&gt; &amp; ❤️</p>", renderer.render("&lt;:smile:&gt; &amp; :heart:"));
    }
}
