package com.yu.babycare.conversation;

import com.yu.babycare.agent.Guide;
import com.yu.babycare.agent.LanguageModel.Context;
import com.yu.babycare.auth.StoreSecurity;
import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.conversation.Conversations.Input;
import com.yu.babycare.conversation.Conversations.Turn;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** One current task per guide and store. The model only sees Context; previous replies are display history. */
@Service
class Checkpoints {
    record Owner(String guide, StoreSecurity.Store store) {}
    record Snapshot(UUID id, int version, List<Turn> turns) {}
    record Claim(UUID token, Context context) {}
    private record Row(String id, int version, Context context, long expires, long claimUntil) {}
    private static final long CLAIM_MILLIS = Duration.ofMinutes(3).toMillis();
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final StoreSecurity security;
    private final long ttl;

    Checkpoints(JdbcTemplate db, ObjectMapper json, StoreSecurity security,
                @Value("${babycare.checkpoint.ttl:24h}") Duration ttl) {
        this.db = db;
        this.json = json;
        this.security = security;
        this.ttl = ttl.toMillis();
        if (this.ttl <= 0) throw new IllegalArgumentException("Checkpoint TTL must be positive");
    }

    Owner owner(Authentication auth) {
        if (!security.hasCurrentRole(auth, "ROLE_GUIDE")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return new Owner(auth.getName(), StoreSecurity.store(auth));
    }

    @Transactional
    public Snapshot open(Owner owner, boolean fresh) {
        String id = UUID.randomUUID().toString();
        String initial = json.writeValueAsString(new Context(Filter.empty(),null));
        long now = System.currentTimeMillis();
        int inserted = db.update("""
            INSERT INTO bc_chat_checkpoint (guide_name,store_id,chat_id,version,context_json,expires_at)
            VALUES (?,?,?,0,?,?) ON CONFLICT DO NOTHING
            """,owner.guide(),owner.store().id(),id,initial,now+ttl);
        Row row = lock(owner);
        if (inserted == 0 && (fresh || row.expires() <= System.currentTimeMillis())) {
            db.update("DELETE FROM bc_chat_turn WHERE chat_id=?",row.id());
            db.update("""
                UPDATE bc_chat_checkpoint SET chat_id=?,version=0,context_json=?,expires_at=?,claim_token=NULL,claim_until=NULL
                WHERE chat_id=?
                """,id,initial,System.currentTimeMillis()+ttl,row.id());
            return new Snapshot(UUID.fromString(id),0,List.of());
        }
        return snapshot(row);
    }

    private Row lock(Owner owner) {
        return db.query("""
            SELECT chat_id,version,context_json,expires_at,claim_until FROM bc_chat_checkpoint
            WHERE guide_name=? AND store_id=? FOR UPDATE
            """,(r,n) -> new Row(r.getString(1),r.getInt(2),json.readValue(r.getString(3),Context.class),
                r.getLong(4),r.getLong(5)),owner.guide(),owner.store().id()).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Row active(Owner owner, UUID id) {
        Row row = lock(owner);
        if (!row.id().equals(id.toString()) || row.expires() <= System.currentTimeMillis())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务已过期或已被替换");
        return row;
    }

    private Snapshot snapshot(Row row) {
        var turns = db.query("""
            SELECT message_id,question,reply_json FROM bc_chat_turn WHERE chat_id=? ORDER BY version
            """,(r,n) -> new Turn(UUID.fromString(r.getString(1)),r.getString(2),
                json.readValue(r.getString(3),Guide.Reply.class)),row.id());
        return new Snapshot(UUID.fromString(row.id()),row.version(),turns);
    }

    @Transactional
    public Snapshot read(Owner owner, UUID id) {
        return snapshot(active(owner,id));
    }

    /** A null token means a committed replay. A crashed worker's lease is reclaimable. */
    @Transactional
    public Claim claim(Owner owner, UUID id, Input input) {
        Row row = active(owner,id);
        var previous = db.queryForList("SELECT question FROM bc_chat_turn WHERE chat_id=? AND message_id=?",
            String.class,row.id(),input.messageId().toString());
        if (!previous.isEmpty()) {
            if (!previous.getFirst().equals(input.text())) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return new Claim(null,null);
        }
        if (row.version()!=input.version()) throw new ResponseStatusException(HttpStatus.CONFLICT,"对话已更新");
        long now = System.currentTimeMillis();
        if (row.claimUntil()>now) throw new ResponseStatusException(HttpStatus.CONFLICT,"本轮正在处理，请稍后重试");
        if (row.version()>=100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请新建对话");
        UUID token = UUID.randomUUID();
        db.update("UPDATE bc_chat_checkpoint SET claim_token=?,claim_until=? WHERE chat_id=?",
            token.toString(),now+CLAIM_MILLIS,row.id());
        return new Claim(token,row.context());
    }

    @Transactional
    public void finish(Owner owner, UUID id, Input input, Claim claim, Guide.Result result) {
        var reply = result.reply();
        var displayed = claim.context().displayedSkus();
        if (result.filter().selectedSku() == null && (reply.type().equals("products") || reply.type().equals("empty")))
            displayed = reply.products().stream().map(product -> product.sku()).toList();
        var context = new Context(result.filter(),reply.type().equals("clarify") ? reply.text() : null,displayed);
        long now = System.currentTimeMillis();
        int updated = db.update("""
            UPDATE bc_chat_checkpoint SET version=version+1,
                context_json=CASE WHEN ? THEN context_json ELSE ? END,expires_at=?,claim_token=NULL,claim_until=NULL
            WHERE guide_name=? AND store_id=? AND chat_id=? AND version=? AND claim_token=? AND expires_at>?
            """,reply.type().equals("access_denied"),json.writeValueAsString(context),now+ttl,owner.guide(),owner.store().id(),id.toString(),
            input.version(),claim.token().toString(),now);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已更新或已过期，请刷新");
        db.update("INSERT INTO bc_chat_turn (chat_id,version,message_id,question,reply_json) VALUES (?,?,?,?,?)",
            id.toString(),input.version()+1,input.messageId().toString(),input.text(),json.writeValueAsString(reply));
    }

    public void release(UUID id, UUID token) {
        db.update("UPDATE bc_chat_checkpoint SET claim_token=NULL,claim_until=NULL WHERE chat_id=? AND claim_token=?",
            id.toString(),token.toString());
    }
}
