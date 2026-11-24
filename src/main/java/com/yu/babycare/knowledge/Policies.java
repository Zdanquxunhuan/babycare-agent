package com.yu.babycare.knowledge;

import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Policies {
    public record Draft(String key, String title, String scope, LocalDate expiresOn, String markdown) {}
    public record Version(String key, int version, String title, String scope, LocalDate expiresOn, String status) {}
    public record Hit(Knowledge.Evidence evidence, int score) {}
    private static final String CURRENT_VERSION = """
        FROM bc_policy_chunk c
        JOIN bc_policy p ON p.policy_key=c.policy_key AND p.active_version=c.rev
        JOIN bc_policy_version v ON v.policy_key=c.policy_key AND v.rev=c.rev
        """;
    private static final String VISIBLE = """
        WHERE (v.store_scope='ALL' OR v.store_scope=?) AND (v.expires_on IS NULL OR v.expires_on>=CURRENT_DATE)
        """;
    private final JdbcTemplate db;
    public Policies(JdbcTemplate db) { this.db = db; }

    @Transactional
    public int upload(Draft draft) {
        if (draft.key()==null || !draft.key().matches("[A-Za-z0-9_-]{1,80}") ||
            draft.title()==null || draft.title().isBlank() || draft.title().length()>120 || draft.title().indexOf(0)>=0 ||
            draft.scope()==null || !Set.of("ALL","STORE-01","STORE-02").contains(draft.scope()) ||
            draft.markdown()==null || draft.markdown().length()>65_536 || draft.markdown().contains("\0"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请检查文档编号、标题、门店以及正文（最多 65536 字符）");
        var chunks = PolicyText.chunks(draft.markdown());
        if (chunks.isEmpty() || chunks.size()>512)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"文档需要 1 至 512 个正文片段");

        db.update("INSERT INTO bc_policy(policy_key) VALUES (?) ON CONFLICT DO NOTHING", draft.key());
        db.queryForObject("SELECT last_published FROM bc_policy WHERE policy_key=? FOR UPDATE",Integer.class,draft.key());
        int version = db.queryForObject("SELECT COALESCE(MAX(rev),0)+1 FROM bc_policy_version WHERE policy_key=?",
            Integer.class,draft.key());
        db.update("INSERT INTO bc_policy_version(policy_key,rev,title,store_scope,expires_on,markdown) VALUES (?,?,?,?,?,?)",
            draft.key(),version,draft.title().strip(),draft.scope(),draft.expiresOn(),draft.markdown());
        int position = 0;
        for (var chunk : chunks) {
            String id = UUID.randomUUID().toString();
            db.update("INSERT INTO bc_policy_chunk(chunk_id,policy_key,rev,position_no,section,excerpt) VALUES (?,?,?,?,?,?)",
                id,draft.key(),version,++position,chunk.section(),chunk.body());
            Map<String,Integer> weights = new HashMap<>();
            PolicyText.terms(chunk.body()).forEach(term -> weights.put(term,1));
            PolicyText.terms(draft.title()+" "+chunk.section()).forEach(term -> weights.merge(term,3,Integer::sum));
            List<Object[]> terms = weights.entrySet().stream()
                .map(entry -> new Object[]{entry.getKey(),id,entry.getValue()}).toList();
            if (!terms.isEmpty()) db.batchUpdate("INSERT INTO bc_policy_term(term,chunk_id,weight) VALUES (?,?,?)",terms);
        }
        return version;
    }

    public List<Version> versions() {
        return db.query("""
            SELECT v.*, CASE WHEN v.rev=p.active_version THEN 'ACTIVE'
            WHEN v.reviewed_by IS NULL AND v.rev>p.last_published THEN 'DRAFT' ELSE 'ARCHIVED' END AS status
            FROM bc_policy_version v JOIN bc_policy p ON p.policy_key=v.policy_key ORDER BY v.policy_key, v.rev DESC
            """,(r,n) -> new Version(r.getString("policy_key"),r.getInt("rev"),r.getString("title"),
                r.getString("store_scope"),r.getObject("expires_on",LocalDate.class),r.getString("status")));
    }

    @Transactional
    public void publish(String key, int rev, String reviewer) {
        int changed = db.update("""
            UPDATE bc_policy SET active_version=?,last_published=?
            WHERE policy_key=? AND last_published<? AND EXISTS
            (SELECT 1 FROM bc_policy_version WHERE policy_key=? AND rev=? AND (expires_on IS NULL OR expires_on>=CURRENT_DATE))
            """,rev,rev,key,rev,key,rev);
        if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"版本已过期、已发布或已被新版替代");
        db.update("UPDATE bc_policy_version SET reviewed_by=? WHERE policy_key=? AND rev=?",reviewer,key,rev);
    }

    public void withdraw(String key, int rev) {
        if (db.update("UPDATE bc_policy SET active_version=NULL WHERE policy_key=? AND active_version=?",key,rev)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"当前生效版本已变更，请刷新");
    }

    public List<Hit> search(String store, Set<String> terms) {
        if (terms.isEmpty()) return List.of();
        String placeholders = String.join(",",Collections.nCopies(terms.size(),"?"));
        String ranked = "SELECT chunk_id,SUM(weight) AS score FROM bc_policy_term WHERE term IN ("+
            placeholders+") GROUP BY chunk_id";
        String sql = "SELECT c.chunk_id,c.section,c.excerpt,v.title,v.rev,t.score " +
            CURRENT_VERSION + " JOIN ("+ranked+") t ON t.chunk_id=c.chunk_id " + VISIBLE +
            " ORDER BY t.score DESC,c.policy_key,c.position_no LIMIT 5";
        var args = new ArrayList<Object>(terms);
        args.add(store);
        return db.query(sql,(r,n) -> {
            String id = r.getString("chunk_id");
            var evidence = new Knowledge.Evidence(id,r.getString("title"),r.getString("rev"),
                "/api/knowledge/citations/"+id,r.getString("excerpt"),r.getString("section"));
            return new Hit(evidence,r.getInt("score"));
        },args.toArray());
    }

    public String original(String key,int rev) {
        return db.queryForList("SELECT markdown FROM bc_policy_version WHERE policy_key=? AND rev=?",String.class,key,rev)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Optional<String> citation(String store,String id) {
        return db.queryForList("SELECT c.excerpt "+CURRENT_VERSION+VISIBLE+" AND c.chunk_id=?",String.class,store,id)
            .stream().findFirst();
    }

    @Transactional
    public void feedback(String user, UUID message, Knowledge.Evidence evidence, boolean helpful) {
        Object[] args = {user,message.toString(),evidence.id(),evidence.version(),helpful};
        db.update("""
            INSERT INTO bc_answer_feedback(guide_name,message_id,evidence_id,version_label,helpful)
            VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
            """,args);
        db.update("UPDATE bc_answer_feedback SET helpful=? WHERE guide_name=? AND message_id=? AND evidence_id=?",
            helpful,user,message.toString(),evidence.id());
    }
}
