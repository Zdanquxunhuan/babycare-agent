package com.yu.babycare.knowledge;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Knowledge {
    public record Evidence(String id, String title, String version, String source, String excerpt) {}
    private final List<Evidence> approved;

    public Knowledge(@Value("${babycare.fixtures:fixtures}") Path dir) throws IOException {
        // Only this reviewed version is admitted into the simulation index.
        String text = Files.readString(dir.resolve("knowledge/hand-hygiene.md"));
        if (!text.contains("摘要版本：1.0") || !text.contains("已确认此版本可用于本地模拟问答")) {
            approved = List.of();
            return;
        }
        String source = text.lines().filter(s -> s.startsWith("原文：https://")).findFirst().orElseThrow();
        String paragraph = text.strip().substring(text.strip().lastIndexOf('\n') + 1).strip();
        approved = List.of(new Evidence("hand-hygiene-1.0", "正确洁手", "1.0", source.substring(3), paragraph));
    }

    public List<Evidence> search(String query) {
        if (List.of("洗手","洁手","手部","手上","手卫生","搓手").stream().anyMatch(query::contains))
            return approved;
        return List.of();
    }
}
