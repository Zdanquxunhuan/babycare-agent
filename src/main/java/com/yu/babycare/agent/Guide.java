package com.yu.babycare.agent;

import com.yu.babycare.auth.StoreSecurity.Store;
import com.yu.babycare.catalog.Catalog;
import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.knowledge.Knowledge;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Guide {
    public record Reply(String type, String text, List<Catalog.Offer> products, List<Knowledge.Evidence> citations) {
        static Reply message(String type, String text) { return new Reply(type,text,List.of(),List.of()); }
    }
    public record Result(Filter filter, Reply reply) {}
    private final Catalog catalog;
    private final Knowledge knowledge;
    private final LanguageModel model;

    public Guide(Catalog catalog, Knowledge knowledge, LanguageModel model) {
        this.catalog = catalog;
        this.knowledge = knowledge;
        this.model = model;
    }

    public Result answer(String question, Store store, Filter previous) {
        if (List.of("呼吸困难","抽搐","意识不清","严重脱水","喘不过气").stream().anyMatch(question::contains))
            return risk(previous);
        var plan = model.interpret(question, store.id(), previous);
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
        var filter = merge(previous, plan);
        if (plan.intent() == LanguageModel.Intent.RECOMMEND_SIZE && filter.size() == null)
            return new Result(filter,Reply.message("clarify","顾客需要什么尺码？可以直接告诉我包装上标的尺码。"));
        try {
            var rows = catalog.query(store, store.id(), filter);
            if (rows.isEmpty()) return new Result(filter,Reply.message("empty","本店没有符合当前条件的商品。要调整预算或品牌吗？"));
            String message = "本店有 " + rows.size() + " 款商品符合当前筛选条件。";
            String missing = plan.missingField();
            if (question.contains("透气")) missing = "透气性";
            if (missing!=null && !missing.isBlank())
                message += " 现有资料缺少可核实的“" + missing + "”信息。";
            return new Result(filter,new Reply("products",message,rows,List.of()));
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode()!=HttpStatus.SERVICE_UNAVAILABLE) throw ex;
            return new Result(filter,Reply.message("stock_unavailable","库存暂无法确认，请稍后重新查询。"));
        }
    }

    private static Result risk(Filter filter) {
        return new Result(filter,Reply.message("safety","请及时联系当地急救或前往医院就医。"));
    }

    private static Filter merge(Filter old, LanguageModel.Plan p) {
        var clear = p.clear()==null ? Set.of() : p.clear();
        var excluded = new HashSet<>(old.excluded());
        if (clear.contains("exclude")) excluded.clear();
        if (p.allow()!=null) excluded.removeAll(p.allow());
        if (p.exclude()!=null) excluded.addAll(p.exclude());
        return new Filter(value(p.category(),old.category(),clear.contains("category")),
            value(p.size(),old.size(),clear.contains("size")),value(p.budget(),old.budget(),clear.contains("budget")),
            p.inStock()!=null ? p.inStock() : old.inStock() && !clear.contains("inStock"),
            p.brands()!=null ? p.brands() : clear.contains("brands") ? Set.of() : old.brands(), Set.copyOf(excluded));
    }

    private static <T> T value(T next,T previous,boolean clear) {
        if (next!=null) return next;
        return clear ? null : previous;
    }
}
