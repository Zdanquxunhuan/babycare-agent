package com.yu.babycare.agent;

import com.yu.babycare.auth.StoreSecurity.Store;
import com.yu.babycare.catalog.Catalog;
import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.knowledge.Knowledge;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Guide {
    public record Reply(String type, String text, List<Catalog.Offer> products, List<Knowledge.Evidence> citations,
                        Integer quantity, Map<String,BigDecimal> totals, List<String> trace) {
        Reply(String type, String text, List<Catalog.Offer> products, List<Knowledge.Evidence> citations) {
            this(type,text,products,citations,null,Map.of(),List.of());
        }
        static Reply message(String type, String text) { return new Reply(type,text,List.of(),List.of()); }
    }
    public record Result(Filter filter, Reply reply) {}
    private final Catalog catalog;
    private final Knowledge knowledge;
    private final LanguageModel model;
    private final PurchasePlanner purchases;

    public Guide(Catalog catalog, Knowledge knowledge, LanguageModel model, PurchasePlanner purchases) {
        this.catalog = catalog;
        this.knowledge = knowledge;
        this.model = model;
        this.purchases = purchases;
    }

    public Result answer(String question, Store store, Filter previous) {
        if (List.of("呼吸困难","抽搐","意识不清","严重脱水","喘不过气").stream().anyMatch(question::contains))
            return risk(previous);
        var mentioned = catalog.mentionedBrands(store,question);
        var plan = model.interpret(question, store.id(), previous, mentioned);
        if (plan.intent() == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型输出无效");
        if (plan.intent() == LanguageModel.Intent.RISK) return risk(previous);
        if (plan.store() != null && !store.id().equals(plan.store()))
            return new Result(previous, Reply.message("access_denied","该门店超出当前账号的查询权限。"));
        if (plan.intent() == LanguageModel.Intent.KNOWLEDGE) {
            var evidence = knowledge.search(question);
            if (!evidence.isEmpty()) {
                String id = model.selectEvidence(question, evidence);
                for (var hit : evidence) if (hit.id().equals(id))
                    return new Result(previous,new Reply("knowledge",hit.excerpt(),List.of(),List.of(hit)));
            }
            return new Result(previous,Reply.message("no_evidence","现有审核资料不足以回答这个问题。"));
        }
        if (plan.intent() == LanguageModel.Intent.OTHER)
            return new Result(previous,Reply.message("clarify","你想了解哪款商品，或查询哪方面的护理资料？"));
        if (plan.intent() == LanguageModel.Intent.MIXED_BASKET)
            return new Result(previous,Reply.message("clarify","当前支持同一款商品多包核算。先查哪一款、几包？"));
        if (plan.quantity()!=null && plan.quantity()<1)
            return new Result(previous,Reply.message("clarify","请提供大于零的整数包数。"));
        if (invalidAmount(plan.budget()) || invalidAmount(plan.totalBudget()))
            return new Result(previous,Reply.message("clarify","请提供非负的预算金额，精确到分。"));
        var filter = merge(previous, plan, question);
        var unresolved = new LinkedHashSet<>(mentioned);
        unresolved.removeAll(filter.brands());
        unresolved.removeAll(filter.excluded());
        if (plan.allow()!=null) unresolved.removeAll(plan.allow());
        if (!unresolved.isEmpty())
            return new Result(filter,Reply.message("clarify","请确认要查询还是排除品牌“" + String.join("、",unresolved) + "”？"));
        if (filter.pendingBudget()!=null)
            return new Result(filter,Reply.message("clarify",filter.pendingBudget()+" 元是每包上限，还是购买这些商品的总预算？"));
        if (filter.totalBudget()!=null && filter.quantity()==null)
            return new Result(filter,Reply.message("clarify","按这个总预算，顾客想买同款几包？"));
        if (plan.intent() == LanguageModel.Intent.RECOMMEND_SIZE && filter.size() == null)
            return new Result(filter,Reply.message("clarify","顾客需要什么尺码？可以直接告诉我包装上标的尺码。"));
        String missing = question.contains("透气") ? "透气性" : plan.missingField();
        if (filter.quantity()!=null) {
            var reply = purchases.run(store, filter);
            if (!reply.products().isEmpty())
                reply = new Reply(reply.type(),describeMissing(reply.text(),missing),reply.products(),
                    reply.citations(),reply.quantity(),reply.totals(),reply.trace());
            return new Result(filter,reply);
        }
        try {
            var rows = catalog.query(store, store.id(), filter);
            if (rows.isEmpty()) return new Result(filter,Reply.message("empty","本店没有符合当前条件的商品。要调整预算或品牌吗？"));
            String message = "本店有 " + rows.size() + " 款商品符合当前筛选条件。";
            return new Result(filter,new Reply("products",describeMissing(message,missing),rows,List.of()));
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode()!=HttpStatus.SERVICE_UNAVAILABLE) throw ex;
            return new Result(filter,Reply.message("stock_unavailable","库存暂无法确认，请稍后重新查询。"));
        }
    }

    private static Result risk(Filter filter) {
        return new Result(filter,Reply.message("safety","请及时联系当地急救或前往医院就医。"));
    }

    private static String describeMissing(String message, String field) {
        if (field==null || field.isBlank()) return message;
        return message + " 现有资料缺少可核实的“" + field + "”信息。";
    }

    private static Filter merge(Filter old, LanguageModel.Plan p, String question) {
        var clear = p.clear()==null ? Set.of() : p.clear();
        var excluded = new HashSet<>(old.excluded());
        if (clear.contains("exclude")) excluded.clear();
        if (p.allow()!=null) excluded.removeAll(p.allow());
        if (p.exclude()!=null) excluded.addAll(p.exclude());
        Integer quantity = value(p.quantity(),old.quantity(),clear.contains("quantity"));
        BigDecimal unit = clear.contains("budget") ? null : old.budget();
        boolean perPack = List.of("每包","单包","每一包","一包最多","单价").stream().anyMatch(question::contains);
        boolean combined = List.of("总预算","合计","总共","一共","总价","总额","总计","加起来","一起不超过","总花费","总支出")
            .stream().anyMatch(question::contains);
        LanguageModel.Scope scope = perPack ? LanguageModel.Scope.UNIT : null;
        if (combined && !perPack) scope = LanguageModel.Scope.TOTAL;
        // The first release treats an unspecified cap as a unit price. Only explicit wording
        // can introduce a new total cap; a follow-up can inherit an already confirmed scope.
        BigDecimal total = clear.contains("totalBudget") ? null : old.totalBudget();
        BigDecimal pending = clear.contains("budget") || clear.contains("totalBudget") ? null : old.pendingBudget();
        BigDecimal amount = p.budget()!=null ? p.budget() : p.totalBudget();
        if (combined && perPack && p.totalBudget()!=null) {
            total = p.totalBudget();
            amount = p.budget();
            pending = null;
        }
        if (amount==null && pending!=null) {
            if (scope==null) scope=p.scope();
            if (scope!=null) amount=pending;
        }
        if (amount!=null) {
            pending=null;
            if (scope==LanguageModel.Scope.UNIT) unit=amount;
            else if (scope==LanguageModel.Scope.TOTAL) total=amount;
            else if (quantity==null || quantity==1) unit=amount;
            else if (old.totalBudget()!=null && old.budget()==null) total=amount;
            else if (old.budget()!=null && old.totalBudget()==null) unit=amount;
            else pending=amount;
        }
        return new Filter(value(p.category(),old.category(),clear.contains("category")),
            value(p.size(),old.size(),clear.contains("size")),unit,
            p.inStock()!=null ? p.inStock() : old.inStock() && !clear.contains("inStock"),
            p.brands()!=null ? p.brands() : clear.contains("brands") ? Set.of() : old.brands(), Set.copyOf(excluded),
            quantity,total,pending);
    }

    private static boolean invalidAmount(BigDecimal amount) {
        return amount!=null && (amount.signum()<0 || amount.stripTrailingZeros().scale()>2 || amount.precision()-amount.scale()>12);
    }

    private static <T> T value(T next,T previous,boolean clear) {
        if (next!=null) return next;
        return clear ? null : previous;
    }
}
