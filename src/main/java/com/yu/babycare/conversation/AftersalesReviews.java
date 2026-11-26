package com.yu.babycare.conversation;

import com.yu.babycare.agent.Guide;
import com.yu.babycare.auth.StoreSecurity;
import com.yu.babycare.knowledge.Knowledge;
import com.yu.babycare.knowledge.Knowledge.Evidence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class AftersalesReviews {
    public record Ticket(UUID id, UUID chat, UUID draftMessage, String store, Guide.ReviewDraft draft,
                         Evidence evidence, String status, String answer, String reviewer) {}
    public record Confirmation(String evidenceId) {}
    public record Resolution(String answer) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Knowledge knowledge;
    private final Checkpoints checkpoints;

    AftersalesReviews(JdbcTemplate db,ObjectMapper json,Knowledge knowledge,Checkpoints checkpoints) {
        this.db=db;
        this.json=json;
        this.knowledge=knowledge;
        this.checkpoints=checkpoints;
    }

    Guide.Result prepare(Guide.Result result,Checkpoints.Snapshot chat,String request,String store) {
        String sku = result.filter().selectedSku();
        var history = chat.turns();
        if (!history.isEmpty() && history.getLast().reply().type().equals("clarify")
                && history.getLast().reply().text().startsWith("售后复核需要定位商品"))
            request = history.getLast().question()+" / "+request;
        for (var turn : history.reversed()) {
            var reply = turn.reply();
            for (var item : reply.products()) {
                if (!item.sku().equals(sku)) continue;
                var draft = new Guide.ReviewDraft(turn.messageId(),sku,item.name(),turn.question(),request,
                    reply.quantity(),reply.totals().get(sku));
                var evidence = reply.policyText()==null ? Optional.<Evidence>empty() : reply.citations().stream()
                    .filter(e -> knowledge.visible(store,e)).findFirst();
                var preview = new Guide.Reply("review_draft",result.reply().text(),List.of(),List.of(),
                    null,Map.of(),List.of(),Map.of(),null,draft).withPolicy(evidence);
                return new Guide.Result(result.filter(),preview);
            }
        }
        return new Guide.Result(result.filter(),new Guide.Reply("clarify","请先查询本店这款商品，再发起售后复核。",
            List.of(),List.of(),null,Map.of(),List.of()));
    }

    // The checkpoint lock serializes confirmation with chat replacement and concurrent submissions.
    @Transactional
    @PostMapping("/api/conversations/{chat}/messages/{message}/review")
    public Ticket confirm(Authentication auth,@PathVariable UUID chat,@PathVariable UUID message,
                          @RequestBody Confirmation seen) {
        var owner = checkpoints.owner(auth);
        var snapshot = checkpoints.read(owner,chat);
        var existing = db.query("SELECT * FROM bc_aftersales_review WHERE chat_id=? AND draft_message=? AND guide_name=? AND store_id=?",
            this::ticket,chat.toString(),message.toString(),owner.guide(),owner.store().id());
        if (!existing.isEmpty()) return existing.getFirst();
        var reply = snapshot.turns().stream().filter(t -> t.messageId().equals(message)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).reply();
        if (reply.reviewDraft()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先生成售后复核草稿");
        Evidence evidence = reply.citations().stream().filter(e -> knowledge.visible(owner.store().id(),e)).findFirst().orElse(null);
        if (!Objects.equals(seen.evidenceId(),evidence==null ? null : evidence.id()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"引用已更新，请刷新草稿后重新确认");
        UUID id = UUID.randomUUID();
        db.update("""
            INSERT INTO bc_aftersales_review(case_id,chat_id,draft_message,store_id,guide_name,draft_json,evidence_json,submitted_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,id.toString(),chat.toString(),message.toString(),owner.store().id(),owner.guide(),
            json.writeValueAsString(reply.reviewDraft()),evidence==null ? null : json.writeValueAsString(evidence),System.currentTimeMillis());
        return new Ticket(id,chat,message,owner.store().id(),reply.reviewDraft(),evidence,"PENDING",null,null);
    }

    private Ticket ticket(ResultSet row,int index) throws SQLException {
        String store = row.getString("store_id");
        String raw = row.getString("evidence_json");
        Evidence evidence = raw==null ? null : json.readValue(raw,Evidence.class);
        if (evidence!=null && !knowledge.visible(store,evidence)) evidence=null;
        String answer = row.getString("answer");
        return new Ticket(UUID.fromString(row.getString("case_id")),UUID.fromString(row.getString("chat_id")),
            UUID.fromString(row.getString("draft_message")),store,json.readValue(row.getString("draft_json"),Guide.ReviewDraft.class),
            evidence,answer==null ? "PENDING" : "ANSWERED",answer,row.getString("answered_by"));
    }

    List<Ticket> forChat(UUID chat,String store) {
        return db.query("SELECT * FROM bc_aftersales_review WHERE chat_id=? AND store_id=? ORDER BY submitted_at",
            this::ticket,chat.toString(),store);
    }

    @GetMapping("/api/my-reviews")
    public List<Ticket> myReviews(Authentication auth) {
        var owner = checkpoints.owner(auth);
        return db.query("SELECT * FROM bc_aftersales_review WHERE store_id=? AND guide_name=? ORDER BY submitted_at DESC",
            this::ticket,owner.store().id(),owner.guide());
    }

    @GetMapping("/api/reviewer/requests")
    public List<Ticket> pending(Authentication auth) {
        return db.query("SELECT * FROM bc_aftersales_review WHERE store_id=? AND answer IS NULL ORDER BY submitted_at",
            this::ticket,StoreSecurity.reviewerStore(auth).id());
    }

    @Transactional
    @PostMapping("/api/reviewer/requests/{id}/answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void answer(Authentication auth,@PathVariable UUID id,@RequestBody Resolution resolution) {
        String store = StoreSecurity.reviewerStore(auth).id();
        String answer = Objects.toString(resolution.answer(),"").strip();
        if (answer.isEmpty() || answer.length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入 1 至 1000 字的复核答复");
        var current = db.query("SELECT * FROM bc_aftersales_review WHERE store_id=? AND case_id=? FOR UPDATE",
            this::ticket,store,id.toString()).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (current.answer()!=null) {
            if (answer.equals(current.answer()) && auth.getName().equals(current.reviewer())) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT,"此复核单已有答复");
        }
        db.update("UPDATE bc_aftersales_review SET answer=?,answered_by=?,answered_at=? WHERE case_id=? AND store_id=?",
            answer,auth.getName(),System.currentTimeMillis(),id.toString(),store);
    }
}
