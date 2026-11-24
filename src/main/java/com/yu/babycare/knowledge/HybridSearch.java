package com.yu.babycare.knowledge;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("hybrid")
class HybridSearch implements ApplicationRunner {
    private final JdbcTemplate db;
    private final BgeClient bge;
    HybridSearch(JdbcTemplate db,BgeClient bge) { this.db=db; this.bge=bge; }

    private record Version(String key,int rev) {}
    private record Passage(String id,String text) {}

    @Override
    public void run(ApplicationArguments args) {
        var versions = db.query("SELECT policy_key,active_version FROM bc_policy WHERE active_version IS NOT NULL",
            (r,n) -> new Version(r.getString(1),r.getInt(2)));
        for (var v : versions) index(v.key(),v.rev());
    }

    void index(String key,int rev) {
        while (true) {
            var batch = db.query("""
                SELECT c.chunk_id,v.title,c.section,c.excerpt FROM bc_policy_chunk c
                JOIN bc_policy_version v ON v.policy_key=c.policy_key AND v.rev=c.rev
                LEFT JOIN bc_bgem3_chunk_v1 b ON b.chunk_id=c.chunk_id
                WHERE c.policy_key=? AND c.rev=? AND b.chunk_id IS NULL ORDER BY c.position_no LIMIT 8
                """,(r,n) -> new Passage(r.getString(1),BgeClient.passage(r.getString(2),r.getString(3),r.getString(4))),key,rev);
            if (batch.isEmpty()) return;
            var vectors = bge.embed(batch.stream().map(Passage::text).toList());
            var rows = new ArrayList<Object[]>();
            for (int i=0;i<batch.size();i++) rows.add(new Object[]{batch.get(i).id(),Arrays.toString(vectors[i])});
            db.batchUpdate("INSERT INTO bc_bgem3_chunk_v1 VALUES (?,CAST(? AS vector)) ON CONFLICT DO NOTHING",rows);
        }
    }

    List<Knowledge.Evidence> search(String store,String query,List<Knowledge.Evidence> lexical,boolean rerank) {
        var vector = bge.embed(List.of(query))[0];
        var dense = db.query("SELECT c.chunk_id,c.section,c.excerpt,v.title,v.rev " + Policies.CURRENT_VERSION
            + " JOIN bc_bgem3_chunk_v1 b ON b.chunk_id=c.chunk_id " + Policies.VISIBLE
            + " ORDER BY b.embedding <=> CAST(? AS vector),c.policy_key,c.position_no LIMIT 5",
            (r,n) -> Policies.evidence(r),store,Arrays.toString(vector));
        var fused = fuse(lexical,dense);
        return (rerank ? bge.rerank(query,fused) : fused).stream().limit(5).toList();
    }

    static List<Knowledge.Evidence> fuse(List<Knowledge.Evidence> lexical,List<Knowledge.Evidence> dense) {
        var scores = new LinkedHashMap<String,Double>();
        var candidates = new HashMap<String,Knowledge.Evidence>();
        for (var ranking : List.of(lexical,dense)) {
            for (int i=0;i<ranking.size();i++) {
                var e = ranking.get(i);
                scores.merge(e.id(),1.0/(61+i),Double::sum);
                candidates.put(e.id(),e);
            }
        }
        // Stable ties use first appearance in the lexical, then dense list.
        return scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed())
            .map(entry -> candidates.get(entry.getKey())).toList();
    }
}
