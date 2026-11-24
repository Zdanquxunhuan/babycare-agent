package com.yu.babycare.knowledge;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles({"rag","hybrid"})
@EnabledIfEnvironmentVariable(named="BABYCARE_VECTOR_TEST",matches="1")
class HybridPolicyTests {
    @Autowired Policies policies;
    @Autowired Knowledge knowledge;
    @Autowired HybridSearch index;
    @Autowired JdbcTemplate db;
    @MockitoBean BgeClient models;

    @Test void publishBackfillAndIsolation() {
        when(models.embed(anyList())).thenAnswer(call -> {
            List<String> texts=call.getArgument(0);
            double[][] vectors=new double[texts.size()][1024];
            for (var v:vectors) v[0]=1;
            return vectors;
        });
        when(models.rerank(anyString(),anyList())).thenAnswer(call -> call.getArgument(1));
        String key="check-"+UUID.randomUUID();
        var first = new Policies.Draft(key,"售后政策","STORE-01",null,"## 拆包\n仅限店长复核。");
        policies.upload(first);
        assertTrue(knowledge.search("STORE-01","不想要了").isEmpty());
        policies.publish(key,1,"ops");
        var original=knowledge.search("STORE-01","不想要了").getFirst();
        assertEquals("1", original.version());
        assertTrue(knowledge.search("STORE-02","不想要了").isEmpty());
        var second = new Policies.Draft(key,"售后政策","STORE-01",null,"## 拆包\n需要提供照片。");
        policies.upload(second);
        assertEquals(List.of(original),knowledge.search("STORE-01","拆包"));
        // An unavailable encoder must leave V1 active even after the provisional pointer update.
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(models).embed(List.of(
            BgeClient.passage(second.title(),"拆包","需要提供照片。")));
        assertThrows(ResponseStatusException.class,() -> policies.publish(key,2,"ops"));
        assertTrue(knowledge.visible("STORE-01",original));

        // Simulate an existing policy first published by V1.0a, then restart the migration twice.
        db.update("DELETE FROM bc_bgem3_chunk_v1 WHERE chunk_id=?",original.id());
        index.run(null);
        index.run(null);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM bc_bgem3_chunk_v1 WHERE chunk_id=?",Integer.class,original.id()));

        var vector=new double[1024]; vector[0]=1;
        doReturn(new double[][]{vector}).when(models).embed(List.of(BgeClient.passage(second.title(),"拆包","需要提供照片。")));
        policies.publish(key,2,"ops");
        var current=knowledge.search("STORE-01","不想要了");
        assertEquals(1,current.size());
        assertEquals("2",current.getFirst().version());
        assertFalse(knowledge.visible("STORE-01",original));
        db.update("UPDATE bc_policy_version SET expires_on=? WHERE policy_key=? AND rev=2",LocalDate.now().minusDays(2),key);
        assertTrue(knowledge.search("STORE-01","不想要了").isEmpty());
        policies.withdraw(key,2);
        assertTrue(knowledge.search("STORE-01","拆包").isEmpty());

        // The lexical hit becomes stale while the query vector is being calculated.
        int rev=policies.upload(first);
        policies.publish(key,rev,"ops");
        String question="拆包";
        doAnswer(call -> {
            policies.withdraw(key,rev);
            return new double[][]{vector};
        }).when(models).embed(List.of(question));
        assertTrue(knowledge.search("STORE-01",question,Knowledge.Mode.RERANK).isEmpty());
        verify(models,never()).rerank(eq(question),argThat(hits -> !hits.isEmpty()));
    }
}
