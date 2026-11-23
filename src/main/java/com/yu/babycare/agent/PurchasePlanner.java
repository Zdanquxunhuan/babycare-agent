package com.yu.babycare.agent;

import com.yu.babycare.auth.StoreSecurity.Store;
import com.yu.babycare.catalog.Catalog;
import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.catalog.Catalog.Offer;
import com.yu.babycare.agent.LanguageModel.Check;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PurchasePlanner {
    private final Catalog catalog;
    private final LanguageModel model;
    private final int maxSteps;
    private final Duration timeout;

    public PurchasePlanner(Catalog catalog, LanguageModel model,
            @Value("${babycare.purchase.max-steps:4}") int maxSteps,
            @Value("${babycare.purchase.timeout:45s}") Duration timeout) {
        this.catalog = catalog;
        this.model = model;
        this.maxSteps = maxSteps;
        this.timeout = timeout;
    }

    public Guide.Reply run(Store store, Filter filter) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> trace = new ArrayList<>();
        if (maxSteps < 1 || remainingMillis(deadline)<=0) return limited(trace);
        List<String> candidates = catalog.search(store, filter);
        trace.add("本店商品检索 · " + candidates.size() + " 款候选");
        if (candidates.isEmpty()) return stop("empty","本店无符合品类、尺码及品牌条件的商品。",trace);
        EnumSet<Check> remaining = EnumSet.of(Check.STOCK);
        if (filter.totalBudget()!=null || filter.budget()!=null) remaining.add(Check.PRICE);
        List<Offer> rows = List.of();
        while (!remaining.isEmpty()) {
            if (trace.size()>=maxSteps || remainingMillis(deadline)<=0) return limited(trace);
            Check next = remaining.iterator().next();
            if (remaining.size()>1) {
                try {
                    next = model.firstCheck(filter, candidates.size(), remainingMillis(deadline));
                } catch (ResponseStatusException ex) {
                    return stop("planning_failed","规划服务暂不可用，请稍后重试。",trace);
                }
                if (next==null || !remaining.contains(next))
                    return stop("planning_failed","无法确认下一步核验工具，请重试。",trace);
                trace.add("选择核验顺序 · " + label(next) + "优先");
                if (trace.size()>=maxSteps || remainingMillis(deadline)<=0) return limited(trace);
            }

            List<Offer> checked;
            try {
                checked = catalog.check(store, candidates, next==Check.STOCK);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode()!=HttpStatus.SERVICE_UNAVAILABLE) throw ex;
                trace.add("库存查询失败");
                return stop("stock_unavailable","库存暂无法确认，请稍后重新查询。",trace);
            }
            if (remainingMillis(deadline)<=0) return limited(trace);
            if (next==Check.STOCK) {
                rows = checked.stream().filter(row -> row.packs()>=filter.quantity()).toList();
            } else {
                rows = checked.stream().filter(row -> within(row.price(),filter.budget())
                    && within(total(row,filter.quantity()),filter.totalBudget())).toList();
            }
            trace.add(label(next) + " · " + checked.size() + " 款中有 " + rows.size() + " 款通过");
            if (rows.isEmpty()) {
                String reason = "候选商品的价格超出当前预算。";
                if (next==Check.STOCK) {
                    int maximum = checked.stream().mapToInt(Offer::packs).max().orElse(0);
                    reason = "需要同款 " + filter.quantity() + " 包，候选商品最多仅有 " + maximum + " 包库存。";
                }
                return stop("empty", reason + "要调整条件吗？", trace);
            }
            candidates = rows.stream().map(Offer::sku).toList();
            remaining.remove(next);
        }
        Map<String,BigDecimal> totals = new LinkedHashMap<>();
        for (Offer row : rows) totals.put(row.sku(), total(row,filter.quantity()));
        trace.add("完成 · 已核实购买数量及合计");
        return new Guide.Reply("products","本店有 " + rows.size() + " 款可按同款 "
            + filter.quantity() + " 包购买。", rows,List.of(),filter.quantity(),totals,trace);
    }

    private static BigDecimal total(Offer row, int quantity) {
        return row.price().multiply(BigDecimal.valueOf(quantity));
    }

    private static boolean within(BigDecimal value, BigDecimal limit) {
        return limit==null || value.compareTo(limit)<=0;
    }

    private static long remainingMillis(long deadline) {
        return TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
    }

    private static String label(Check check) {
        return check==Check.PRICE ? "价格核验" : "库存核验";
    }

    private static Guide.Reply limited(List<String> trace) {
        return stop("planning_limit","已达到本轮步骤或耗时上限，核验未完成。请重试。",trace);
    }

    private static Guide.Reply stop(String type,String text,List<String> trace) {
        trace.add("停止 · " + text);
        return new Guide.Reply(type,text,List.of(),List.of(),null,Map.of(),trace);
    }
}
