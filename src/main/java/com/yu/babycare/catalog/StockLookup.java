package com.yu.babycare.catalog;

import com.yu.babycare.catalog.Catalog.Fixture;
import com.yu.babycare.catalog.Catalog.Offer;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Reads the simulated stock source under a deadline, including the source I/O itself. */
@RestController
public class StockLookup {
    private static final Logger log = LoggerFactory.getLogger(StockLookup.class);

    public record Alert(Instant at, String messageId, String store, String tool, String outcome,
                        long elapsedMs, long timeoutMs) {}

    private final StoreStockMode mode;
    private final ObjectMapper mapper;
    private final Path source;
    private final Path offlineFlag;
    private final Path slowFlag;
    private final Duration simulatedDelay;
    private final long timeoutMs;
    // No queue: a stuck source cannot accumulate waiting requests or unbounded background work.
    private final ExecutorService workers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("stock-lookup-", 0).factory());
    private final Deque<Alert> alerts = new ArrayDeque<>();

    public StockLookup(ObjectMapper mapper, StoreStockMode mode,
            @Value("${babycare.stock-source}") Path source,
            @Value("${babycare.stock-fault-file:}") String offlineFlag,
            @Value("${babycare.stock-delay-file}") String slowFlag,
            @Value("${babycare.stock-simulated-delay}") Duration simulatedDelay,
            @Value("${babycare.stock-timeout}") Duration timeout) {
        this.mode = mode;
        this.mapper = mapper;
        this.source = source;
        this.offlineFlag = offlineFlag.isBlank() ? null : Path.of(offlineFlag);
        this.slowFlag = slowFlag.isBlank() ? null : Path.of(slowFlag);
        this.simulatedDelay = simulatedDelay;
        this.timeoutMs = timeout.toMillis();
        if (timeoutMs < 1 || simulatedDelay.isNegative()) throw new IllegalArgumentException("库存耗时配置无效");
    }

    List<Offer> lookup(String store, List<Offer> candidates, long remainingMs) {
        mode.requireAutomatic(store);
        long budget = Math.min(timeoutMs, Math.max(0, remainingMs));
        long start = System.nanoTime();
        Future<List<Offer>> task;
        try {
            task = workers.submit(() -> read(store, candidates));
        } catch (RejectedExecutionException busy) {
            throw unavailable(busy);
        }
        try {
            var rows = task.get(Math.max(0, TimeUnit.MILLISECONDS.toNanos(budget) - (System.nanoTime() - start)),
                TimeUnit.NANOSECONDS);
            mode.requireAutomatic(store);
            return rows;
        } catch (TimeoutException timeout) {
            task.cancel(true);
            recordTimeout(store, start, budget);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "库存查询超时", timeout);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable(interrupted);
        } catch (ExecutionException failure) {
            throw unavailable(failure.getCause());
        }
    }

    private List<Offer> read(String store, List<Offer> candidates) throws java.io.IOException, InterruptedException {
        if (offlineFlag != null && Files.exists(offlineFlag)) throw new java.io.IOException("库存离线");
        if (slowFlag != null && Files.exists(slowFlag)) Thread.sleep(simulatedDelay);
        Map<String,Integer> packs = new HashMap<>();
        try (var input = Files.newInputStream(source)) {
            var snapshot = mapper.readValue(input, Fixture.class);
            for (var product : snapshot.products()) {
                for (var offer : product.store_offers()) {
                    if (!store.equals(offer.store_id())) continue;
                    Integer count = offer.available_packs();
                    if (count == null || count < 0 || packs.putIfAbsent(product.sku(),count) != null)
                        throw new IllegalStateException("库存样本数据无效");
                }
            }
        }
        Instant now = Instant.now();
        return candidates.stream().map(row -> {
            Integer count = packs.get(row.sku());
            if (count == null) throw new IllegalStateException("缺少门店商品库存");
            return new Offer(row.sku(),row.name(),row.brand(),row.category(),row.size(),row.pieces(),
                row.price(),count,row.snapshot(),now);
        }).toList();
    }

    private static ResponseStatusException unavailable(Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "库存暂无法确认", cause);
    }

    private void recordTimeout(String store, long start, long budget) {
        String id = MDC.get("messageId");
        if (id == null) id = UUID.randomUUID().toString(); // direct offer lookup outside a conversation
        var alert = new Alert(Instant.now(), id, store, "stock.lookup", "timeout",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), budget);
        synchronized (alerts) {
            if (alerts.size() >= 50) alerts.removeLast();
            alerts.addFirst(alert);
        }
        log.warn(
            "tool={} outcome={} messageId={} store={} elapsedMs={} timeoutMs={}",
            alert.tool(),alert.outcome(),id,store,alert.elapsedMs(),budget);
    }

    @GetMapping("/api/ops/alerts/stock-timeouts")
    public List<Alert> recentTimeouts() {
        synchronized (alerts) { return List.copyOf(alerts); }
    }

    @PreDestroy
    void close() { workers.shutdownNow(); }
}
