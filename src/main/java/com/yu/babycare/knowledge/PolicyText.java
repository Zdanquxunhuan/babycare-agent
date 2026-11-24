package com.yu.babycare.knowledge;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Shared indexing/query tokenizer: overlapping Han pairs and complete alphanumeric words. */
public final class PolicyText {
    private PolicyText() {}
    private static final Pattern WORD = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*$");
    public record Chunk(String section, String body) {}

    public static Set<String> terms(String text) {
        var words = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        Set<String> found = new LinkedHashSet<>();
        while (words.find()) {
            String word = words.group();
            int[] chars = word.codePoints().toArray();
            if (Character.UnicodeScript.of(chars[0]) == Character.UnicodeScript.HAN && chars.length > 1) {
                for (int i = 1; i < chars.length; i++) found.add(new String(chars, i - 1, 2));
            } else if (word.length() <= 80) {
                found.add(word);
            }
        }
        return found;
    }

    public static List<Chunk> chunks(String markdown) {
        List<Chunk> result = new ArrayList<>();
        String[] headings = new String[6];
        String section = "正文";
        StringBuilder paragraph = new StringBuilder();
        for (String line : markdown.lines().toList()) {
            var h = HEADING.matcher(line);
            if (h.matches() || line.isBlank()) {
                flush(result, section, paragraph);
                if (h.matches()) {
                    int level = h.group(1).length() - 1;
                    Arrays.fill(headings, level, headings.length, null);
                    headings[level] = h.group(2).strip();
                    section = String.join(" / ", Arrays.stream(headings).filter(Objects::nonNull).toList());
                }
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line.strip());
            }
        }
        flush(result, section, paragraph);
        return result;
    }

    private static void flush(List<Chunk> chunks, String section, StringBuilder paragraph) {
        String body = paragraph.toString();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + 800, body.length());
            if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) end--;
            chunks.add(new Chunk(section, body.substring(start, end)));
            start = end;
        }
        paragraph.setLength(0);
    }
}
