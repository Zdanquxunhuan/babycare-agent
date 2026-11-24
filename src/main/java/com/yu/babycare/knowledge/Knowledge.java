package com.yu.babycare.knowledge;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Knowledge {
    public record Evidence(String id, String title, String version, String source, String excerpt, String section) {}
    private final Policies policies;
    private final Optional<Evidence> publicSample;
    private final Set<String> sampleTerms;

    public Knowledge(@Value("${babycare.fixtures:fixtures}") Path dir, Policies policies) throws IOException {
        this.policies = policies;
        String text = Files.readString(dir.resolve("knowledge/hand-hygiene.md"));
        if (text.contains("摘要版本：1.0") && text.contains("已确认此版本可用于本地模拟问答")) {
            String source = text.lines().filter(s -> s.startsWith("原文：https://")).findFirst().orElseThrow();
            String paragraph = text.strip().substring(text.strip().lastIndexOf('\n') + 1).strip();
            publicSample = Optional.of(new Evidence("hand-hygiene-1.0","正确洁手","1.0",source.substring(3),paragraph,"测试摘要"));
            sampleTerms = PolicyText.terms("正确洁手 "+paragraph+" "+text.lines().filter(s -> s.startsWith("检索词："))
                .findFirst().orElse(""));
        } else {
            publicSample = Optional.empty();
            sampleTerms = Set.of();
        }
    }

    public List<Evidence> search(String store,String query) {
        Set<String> terms = PolicyText.terms(query);
        var hits = new ArrayList<>(policies.search(store,terms));
        int overlap = (int) terms.stream().filter(sampleTerms::contains).count();
        if (overlap>0) publicSample.ifPresent(e -> hits.add(new Policies.Hit(e,overlap)));
        return hits.stream().sorted(Comparator.comparingInt(Policies.Hit::score).reversed()).limit(5).map(Policies.Hit::evidence).toList();
    }

    public boolean visible(String store,Evidence evidence) {
        return publicSample.filter(evidence::equals).isPresent() || policies.citation(store,evidence.id()).isPresent();
    }
}
