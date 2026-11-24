package com.yu.babycare.knowledge;

import com.yu.babycare.agent.LanguageModel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"rag","hybrid"})
@EnabledIfEnvironmentVariable(named="BABYCARE_COMPARE",matches="1")
class HybridComparisonTests {
    @Autowired Knowledge knowledge;
    @Autowired Policies policies;
    @Autowired JdbcTemplate db;
    @Autowired LanguageModel model;

    @Test @Transactional void compareFixedSnapshot() throws Exception {
        // Run against an isolated comparison database; fail before sending any existing policy to a model.
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM bc_policy",Integer.class),
            "请为评测使用空的独立数据库");
        String sample = Files.readString(Path.of("fixtures/policies/returns-v1.md"));
        int version = policies.upload(new Policies.Draft("benchmark","模拟售后政策","STORE-01",null,sample));
        policies.publish("benchmark",version,"test");
        boolean live = System.getenv("DASHSCOPE_API_KEY")!=null && !System.getenv("DASHSCOPE_API_KEY").isBlank();
        var lines = new ArrayList<>(List.of("# V1.0b 固定政策对照", "",
            "直营店 · 模拟售后政策 V1 · 实际 BGE 模型 · 每种策略最多 5 条。检索耗时包含本地模型，不含答案选择。",
            "", "| 问法 | 策略 | 第4条排名 | 候选顺序 | 最终引用 | 检索毫秒 |",
            "|---|---|---|---|---|---|"));
        for (var query : List.of("政策第4条怎么处理","顾客拆包了还能退吗？","包装打开了，客人又不想要了，怎么办？","发票抬头怎么改？")) {
            for (var mode : Knowledge.Mode.values()) {
                long start = System.nanoTime();
                var hits = knowledge.search("STORE-01",query,mode);
                long millis = (System.nanoTime()-start)/1_000_000;
                var sections = hits.stream().map(Knowledge.Evidence::section).toList();
                int position = sections.indexOf("模拟售后政策 / 第4条 已拆包")+1;
                String rank = query.startsWith("发票") ? "无答案题" : position==0 ? "未召回" : Integer.toString(position);
                String citation = hits.isEmpty() ? "无" : "未调用百炼";
                if (live && !hits.isEmpty()) {
                    String id = model.selectEvidence(query,hits);
                    citation = hits.stream().filter(e -> e.id().equals(id) && knowledge.visible("STORE-01",e))
                        .map(Knowledge.Evidence::section).findFirst().orElse("无");
                }
                lines.add("| "+query+" | "+mode+" | "+rank+" | "+sections+" | "+citation+" | "+millis+" |");
                if (query.startsWith("包装") && mode==Knowledge.Mode.LEXICAL) assertEquals(0,position);
                if (mode!=Knowledge.Mode.LEXICAL) assertEquals(2,sections.size(),"两条授权条款都在向量候选范围");
            }
        }
        Path report=Path.of("target/hybrid-comparison.md");
        Files.write(report,lines);
        lines.forEach(System.out::println);
        assertTrue(knowledge.search("STORE-02","顾客拆包了还能退吗？",Knowledge.Mode.RERANK).isEmpty());
        policies.withdraw("benchmark",version);
        assertTrue(knowledge.search("STORE-01","顾客拆包了还能退吗？",Knowledge.Mode.RERANK).isEmpty());
    }
}
