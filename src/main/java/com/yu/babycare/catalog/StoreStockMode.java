package com.yu.babycare.catalog;

import com.yu.babycare.auth.StoreSecurity;
import com.yu.babycare.auth.StoreSecurity.Store;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ops/stock-mode")
public class StoreStockMode {
    public static final String MANUAL_MESSAGE = "本店库存核验已暂停，请到 POS 核实。";
    public static final class Paused extends ResponseStatusException {
        Paused() { super(HttpStatus.LOCKED, MANUAL_MESSAGE); }
    }

    public record Change(long revision, boolean paused, String operator, String reason, Instant changedAt) {}
    public record Status(Store store, Change current) {}
    public record Request(Long expectedRevision, Boolean paused, String reason) {}

    private static final Change INITIAL = new Change(0, false, null, null, null);
    private static final String HISTORY = """
        SELECT revision,paused,operator_name,reason,changed_at FROM bc_stock_mode_change
        WHERE store_id=? ORDER BY revision DESC
        """;
    private static final RowMapper<Change> ROW = (r,n) -> new Change(r.getLong(1),r.getBoolean(2),
        r.getString(3),r.getString(4),Instant.ofEpochMilli(r.getLong(5)));
    private final JdbcTemplate db;

    public StoreStockMode(JdbcTemplate db) { this.db = db; }

    private Change current(String storeId) {
        return db.query(HISTORY + " LIMIT 1", ROW, storeId).stream().findFirst().orElse(INITIAL);
    }

    public boolean isPaused(String storeId) { return current(storeId).paused(); }

    void requireAutomatic(String storeId) {
        if (isPaused(storeId)) throw new Paused();
    }

    private Store knownStore(String id) {
        return StoreSecurity.stores().stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未知门店"));
    }

    @GetMapping
    public List<Status> list() {
        return StoreSecurity.stores().stream().map(s -> new Status(s,current(s.id()))).toList();
    }

    @GetMapping("/{id}/history")
    public List<Change> history(@PathVariable String id) {
        return db.query(HISTORY + " LIMIT 50", ROW, knownStore(id).id());
    }

    @PostMapping("/{id}")
    public Change change(@PathVariable String id, @RequestBody Request request, Authentication auth) {
        knownStore(id);
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (request.expectedRevision() == null || request.expectedRevision() < 0 || request.paused() == null
                || reason.isEmpty() || reason.length() > 240)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择操作，并填写 1 至 240 字的原因");
        var previous = current(id);
        if (request.expectedRevision() != previous.revision()) throw stale();
        if (request.paused() == previous.paused()) return previous;
        var next = new Change(previous.revision()+1,request.paused(),auth.getName(),reason,
            Instant.ofEpochMilli(System.currentTimeMillis()));
        int inserted = db.update("""
            INSERT INTO bc_stock_mode_change(store_id,revision,paused,operator_name,reason,changed_at)
            VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """,id,next.revision(),next.paused(),next.operator(),next.reason(),next.changedAt().toEpochMilli());
        if (inserted == 0) throw stale();
        return next;
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "该门店状态已更新，请核对后再操作");
    }
}
