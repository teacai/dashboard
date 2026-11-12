package com.teacai.wiki.service;

import jakarta.inject.Singleton;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.List;

@Singleton
public class MarkdownRenderer {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final Safelist safelist;

    public MarkdownRenderer() {
        var extensions = List.of(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create());
        parser = Parser.builder().extensions(extensions)
                .maxOpenBlockParsers(100).maxInlineNesting(100).build();
        renderer = HtmlRenderer.builder().extensions(extensions).sanitizeUrls(true).build();
        safelist = Safelist.relaxed()
                .addTags("del", "s", "ins", "abbr", "details", "summary", "figure", "figcaption", "div", "hr")
                .addAttributes("code", "class")
                .preserveRelativeLinks(true);
    }

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        return Jsoup.clean(renderer.render(parser.parse(markdown)), "https://wiki.invalid/", safelist,
                new Document.OutputSettings().prettyPrint(false)).strip();
    }
}
