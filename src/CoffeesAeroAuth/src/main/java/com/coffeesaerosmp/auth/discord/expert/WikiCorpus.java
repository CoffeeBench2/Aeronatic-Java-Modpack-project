package com.coffeesaerosmp.auth.discord.expert;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The public wiki, bundled in the jar ({@code coffeebot/wiki/*.md}, listed in {@code index.txt}) and split into
 * sections at every {@code ##}/{@code ###} heading. Each section becomes a {@link Bm25Index.Doc} that links straight
 * to its anchor on the published wiki. Quartz builds anchors by lowercasing the heading and turning spaces into
 * hyphens after dropping punctuation, which {@link #anchor} mirrors.
 */
public final class WikiCorpus {

    static final String DIR = "/assets/coffees_aero_auth/coffeebot/wiki/";

    private WikiCorpus() {}

    public static List<Bm25Index.Doc> load(String wikiBase) {
        List<Bm25Index.Doc> out = new ArrayList<>();
        for (String file : read(DIR + "index.txt").split("\\R")) {
            file = file.trim();
            if (file.isEmpty() || !file.endsWith(".md")) continue;
            out.addAll(sections(file.substring(0, file.length() - 3), read(DIR + file), wikiBase));
        }
        return out;
    }

    static List<Bm25Index.Doc> sections(String page, String md, String wikiBase) {
        List<Bm25Index.Doc> out = new ArrayList<>();
        String pageUrl = wikiBase + "/" + ("index".equals(page) ? "" : page);
        String pageTitle = page;
        String title = null;
        StringBuilder body = new StringBuilder();
        boolean frontMatter = false;
        int lineNo = 0;
        for (String line : md.split("\\R")) {
            lineNo++;
            if (lineNo == 1 && line.equals("---")) { frontMatter = true; continue; }
            if (frontMatter) { if (line.equals("---")) frontMatter = false; continue; }
            if (line.startsWith("# ")) { pageTitle = line.substring(2).trim(); title = pageTitle; continue; }
            if (line.startsWith("## ") || line.startsWith("### ")) {
                flush(out, page, pageTitle, title, body, pageUrl);
                title = line.replaceFirst("^#+\\s*", "").trim();
                body.setLength(0);
                continue;
            }
            body.append(line).append('\n');
        }
        flush(out, page, pageTitle, title, body, pageUrl);
        return out;
    }

    private static void flush(List<Bm25Index.Doc> out, String page, String pageTitle, String title, StringBuilder body,
                              String pageUrl) {
        String text = plain(body.toString());
        if (title == null || text.length() < 20) return;
        String url = title.equals(pageTitle) ? pageUrl : pageUrl + "#" + anchor(title);
        String shown = title.equals(pageTitle) ? pageTitle : pageTitle + ": " + title;
        out.add(new Bm25Index.Doc(page + "#" + anchor(title), shown, text, url));
    }

    /** Markdown → readable text for an answer: wiki links, callout markers, tables and html dropped. */
    static String plain(String md) {
        return md.replaceAll("<[^>]+>", " ")
            .replaceAll("\\[\\[([^\\]|]+)\\|?([^\\]]*)]]", "$1")
            .replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1")
            .replaceAll("(?m)^>\\s?\\[![a-z]+]\\s*", "")
            .replaceAll("(?m)^>\\s?", "")
            .replaceAll("(?m)^\\|[-|: ]+\\|\\s*$", "")
            .replaceAll("(?m)^```.*$", "")
            .replaceAll("(?m)^---\\s*$", "")
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    static String anchor(String heading) {
        // github-slugger (what Quartz uses): lowercase, drop everything but letters, digits, spaces, '-' and '_',
        // then EACH space becomes '-' (no collapsing: "Ships & claims" -> "ships--claims").
        return heading.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N} _-]", "")
            .replace(' ', '-');
    }

    private static String read(String path) {
        try (InputStream in = WikiCorpus.class.getResourceAsStream(path)) {
            if (in == null) return "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                return sb.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
