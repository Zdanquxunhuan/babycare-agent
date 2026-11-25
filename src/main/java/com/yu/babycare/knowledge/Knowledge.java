package com.yu.babycare.knowledge;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Knowledge {
    public record Evidence(String id, String title, String version, String source, String excerpt, String section) {}
    public enum Mode { LEXICAL, HYBRID, RERANK }
    private final Optional<HybridSearch> hybrid;
    private final Mode mode;
    private final Policies policies;
    private final Optional<Evidence> publicSample;
    private final Set<String> sampleTerms;

    public Knowledge(@Value("${babycare.fixtures:fixtures}") Path dir, Policies policies,
                     Optional<HybridSearch> hybrid, @Value("${babycare.retrieval.mode:LEXICAL}") Mode mode) throws IOException {
        this.hybrid=hybrid;
        this.mode=mode;
        if (mode!=Mode.LEXICAL && hybrid.isEmpty()) throw new IllegalArgumentException("混合检索需要 hybrid profile");
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
        return search(store,query,mode);
    }

    List<Evidence> search(String store,String query,Mode selected) {
        return search(store,query,selected,true);
    }

    public List<Evidence> searchPolicies(String store,String query) {
        return search(store,query,mode,false);
    }

    private List<Evidence> search(String store,String query,Mode selected,boolean includeSample) {
        Set<String> terms = PolicyText.terms(query);
        // “拆了一包”与“已拆包”没有连续的汉字二元词；只扩展召回，选证据仍使用完整原问。
        if (query.matches("(?s).*拆(?:开)?了(?:一|1)包.*")) terms.add("拆包");
        var hits = new ArrayList<>(policies.search(store,terms));
        int overlap = (int) terms.stream().filter(sampleTerms::contains).count();
        if (includeSample && overlap>0) publicSample.ifPresent(e -> hits.add(new Policies.Hit(e,overlap)));
        var lexical = hits.stream().sorted(Comparator.comparingInt(Policies.Hit::score).reversed()).limit(5).map(Policies.Hit::evidence).toList();
        var result = selected == Mode.LEXICAL ? lexical :
            hybrid.orElseThrow().search(store,query,lexical,selected == Mode.RERANK,e -> visible(store,e));
        return result.stream().filter(e -> visible(store,e)).toList();
    }

    public boolean visible(String store,Evidence evidence) {
        return publicSample.filter(evidence::equals).isPresent() || policies.citation(store,evidence.id()).isPresent();
    }
}
