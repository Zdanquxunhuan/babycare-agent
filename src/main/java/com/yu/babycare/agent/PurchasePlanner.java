package com.yu.babycare.agent;

import com.yu.babycare.auth.StoreSecurity.Store;
import com.yu.babycare.catalog.Catalog;
import com.yu.babycare.catalog.Promotions;
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
    public record Quote(String name, BigDecimal regularTotal, String activity, BigDecimal discount,
                        BigDecimal payable, boolean meetsBudget) {}
    private final Catalog catalog;
    private final Promotions promotions;
    private final LanguageModel model;
    private final int maxSteps;
    private final Duration timeout;

    public PurchasePlanner(Catalog catalog, Promotions promotions, LanguageModel model,
            @Value("${babycare.purchase.max-steps:5}") int maxSteps,
            @Value("${babycare.purchase.timeout:45s}") Duration timeout) {
        this.catalog = catalog;
        this.promotions = promotions;
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
        boolean checkActivity = Boolean.TRUE.equals(filter.promotions());
        if (checkActivity) remaining.add(Check.PROMOTION);
        if (checkActivity || filter.totalBudget()!=null || filter.budget()!=null) remaining.add(Check.PRICE);
        boolean first = true;
        boolean activityUnavailable = false;
        Optional<Promotions.Rule> rule = Optional.empty();
        Map<String,BigDecimal> verifiedPrices = new HashMap<>();
        List<Offer> inStock = List.of();
        List<Offer> rows = List.of();
        while (!remaining.isEmpty()) {
            if (trace.size()>=maxSteps || remainingMillis(deadline)<=0) return limited(trace);
            Check next = remaining.iterator().next();
            if (first && remaining.size()>1) {
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

            first = false;
            remaining.remove(next);
            if (next==Check.PROMOTION) {
                try {
                    rule = promotions.lookup(store,candidates,filter.quantity());
                    trace.add(rule.isPresent() ? "活动核验 · 有适用活动" : "活动核验 · 无适用活动");
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode()!=HttpStatus.SERVICE_UNAVAILABLE) throw ex;
                    activityUnavailable = true;
                    trace.add("活动查询失败 · 使用已核实普通价");
                }
                if (remainingMillis(deadline)<=0) return limited(trace);
                continue;
            }

            List<Offer> checked;
            try {
                checked = catalog.check(store, candidates, next==Check.STOCK, remainingMillis(deadline));
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode()==HttpStatus.GATEWAY_TIMEOUT) {
                    trace.add("库存查询超时");
                    return stop("stock_unavailable","库存暂无法确认，请稍后再查或请店员核实。",trace);
                }
                if (ex.getStatusCode()!=HttpStatus.SERVICE_UNAVAILABLE) throw ex;
                trace.add("库存查询失败");
                return stop("stock_unavailable","库存暂无法确认，请稍后重新查询。",trace);
            }
            if (remainingMillis(deadline)<=0) return limited(trace);
            if (next==Check.STOCK) {
                rows = checked.stream().filter(row -> row.packs()>=filter.quantity()).toList();
                inStock = rows;
            } else if (checkActivity) {
                // A regular total over budget may still qualify after the promotion check.
                checked.forEach(row -> verifiedPrices.put(row.sku(),row.price()));
                rows = checked;
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
        }
        if (checkActivity) return promotionReply(filter,inStock,verifiedPrices,rule,activityUnavailable,trace);
        // A later price check must not replace a freshly verified quantity with the startup snapshot.
        var eligibleSkus = Set.copyOf(candidates);
        rows = inStock.stream().filter(row -> eligibleSkus.contains(row.sku())).toList();
        Map<String,BigDecimal> totals = new LinkedHashMap<>();
        for (Offer row : rows) totals.put(row.sku(), total(row,filter.quantity()));
        trace.add("完成 · 已核实购买数量及合计");
        return new Guide.Reply("products","本店有 " + rows.size() + " 款可按同款 "
            + filter.quantity() + " 包购买。", rows,List.of(),filter.quantity(),totals,trace);
    }

    private static Guide.Reply promotionReply(Filter filter, List<Offer> inStock, Map<String,BigDecimal> prices,
                                              Optional<Promotions.Rule> rule, boolean unavailable, List<String> trace) {
        List<Offer> matches = new ArrayList<>();
        Map<String,BigDecimal> totals = new LinkedHashMap<>();
        Map<String,Quote> quotes = new LinkedHashMap<>();
        for (Offer row : inStock) {
            BigDecimal price = prices.get(row.sku());
            if (price == null) continue;
            BigDecimal quantity = BigDecimal.valueOf(filter.quantity());
            BigDecimal regular = price.multiply(quantity);
            var applicable = rule.filter(r -> r.sku().equals(row.sku()));
            BigDecimal discount = applicable.map(r -> r.discount().min(regular)).orElse(BigDecimal.ZERO);
            BigDecimal payable = regular.subtract(discount);
            boolean fits = within(payable,filter.totalBudget())
                && (filter.budget()==null || payable.compareTo(filter.budget().multiply(quantity))<=0);
            if (fits) {
                matches.add(new Offer(row.sku(),row.name(),row.brand(),row.category(),row.size(),row.pieces(),
                    price,row.packs(),row.snapshot(),row.queriedAt()));
                totals.put(row.sku(),payable);
            }
            if (fits || unavailable) {
                quotes.put(row.sku(),new Quote(row.name(),regular,applicable.map(Promotions.Rule::title).orElse(null),
                    unavailable ? null : discount,unavailable ? null : payable,fits));
            }
        }
        String message;
        if (unavailable) {
            message = "优惠待确认。按已核实的普通价，有 " + matches.size() + " 款符合当前购买条件。";
        } else {
            message = matches.isEmpty() ? "本店核验活动后没有符合当前预算的有货商品。"
                : "本店核验活动后有 " + matches.size() + " 款有货商品符合当前购买条件。";
        }
        trace.add("完成 · 复核购买数量及预算");
        return new Guide.Reply(matches.isEmpty() ? "empty" : "products",message,matches,List.of(),
            filter.quantity(),totals,trace,quotes);
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
        return switch (check) {
            case PRICE -> "价格核验";
            case STOCK -> "库存核验";
            case PROMOTION -> "活动核验";
        };
    }

    private static Guide.Reply limited(List<String> trace) {
        return stop("planning_limit","已达到本轮步骤或耗时上限，核验未完成。请重试。",trace);
    }

    private static Guide.Reply stop(String type,String text,List<String> trace) {
        trace.add("停止 · " + text);
        return new Guide.Reply(type,text,List.of(),List.of(),null,Map.of(),trace);
    }
}
