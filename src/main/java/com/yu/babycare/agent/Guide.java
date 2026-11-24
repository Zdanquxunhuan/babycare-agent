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

/**
 * 导购 Agent 的核心决策入口 / Core decision entry point of the guide agent.
 *
 * <p>一次 {@link #answer} 调用把一个自然语言问题变成一条 {@link Reply}，同时给出可跨轮继承的
 * {@link Filter}：先做安全与权限兜底，再让模型把问题解析成结构化计划，最后交给商品目录或
 * {@link PurchasePlanner} 完成检索、价格与库存核验。
 * A single {@link #answer} call turns a natural-language question into a {@link Reply} plus a
 * {@link Filter} that the next turn can inherit: safety and permission guards run first, the
 * model maps the question to a structured plan, and catalog lookup or {@link PurchasePlanner}
 * finally performs retrieval, price and stock verification.
 *
 * <p>设计约束 / Design constraints:
 * <ul>
 *   <li>模型只负责理解与选择，不产出生事实：商品、价格、引用都来自目录、知识库与核验工具。
 *       The model only interprets and selects; product facts, prices and citations always come
 *       from the catalog, knowledge base and verification tools.</li>
 *   <li>任何“条件还不够”的追问也会返回已合并的 {@link Filter}，下一轮可直接继承。
 *       Every clarifying question returns the merged {@link Filter} so the next turn inherits
 *       the partial conditions instead of starting over.</li>
 *   <li>模型不可用或输出无效时返回 503 或降级话术，绝不编造答案。
 *       Model failure or invalid output degrades to 503 or a fallback message; answers are never
 *       fabricated.</li>
 * </ul>
 */
@Service
public class Guide {
    /**
     * 一轮回答 / One answer returned to the caller.
     *
     * <p>{@code type} 是机器可读的结果码，前端与验收测试据此分支：{@code products}（商品列表）、
     * {@code knowledge}（资料引用）、{@code clarify}（追问）、{@code safety}（就医提示）、
     * {@code empty}（无匹配）、{@code no_evidence}（资料不足）、{@code access_denied}（越权门店）、
     * {@code stock_unavailable}（库存不可用）等；{@code text} 是展示给导购的话术。
     * {@code type} is the machine-readable outcome code consumed by the UI and acceptance tests
     * ({@code products}, {@code knowledge}, {@code clarify}, {@code safety}, {@code empty},
     * {@code no_evidence}, {@code access_denied}, {@code stock_unavailable}, ...);
     * {@code text} is the human-readable message shown to the guide.
     *
     * @param type      结果码 / outcome code
     * @param text      展示话术 / message shown to the user
     * @param products  命中的商品，可能为空 / matched offers, possibly empty
     * @param citations 引用的已审核资料，仅知识类回答有值 / cited approved sources, only for knowledge answers
     * @param quantity  每款商品购买的包数，仅购买规划结果有值 / packs per SKU, set only by purchase planning
     * @param totals    SKU → 合计金额，仅购买规划结果有值 / SKU → total amount, set only by purchase planning
     * @param trace     规划步骤日志，便于排查 / planning step log for troubleshooting
     */
    public record Reply(String type, String text, List<Catalog.Offer> products, List<Knowledge.Evidence> citations,
                        Integer quantity, Map<String,BigDecimal> totals, List<String> trace,
                        Map<String,PurchasePlanner.Quote> quotes) {
        public Reply(String type, String text, List<Catalog.Offer> products, List<Knowledge.Evidence> citations,
                     Integer quantity, Map<String,BigDecimal> totals, List<String> trace) {
            this(type,text,products,citations,quantity,totals,trace,Map.of());
        }
        /** 便捷构造：非购买类回复没有包数与合计。/ Convenience constructor for replies without quantity or totals. */
        Reply(String type, String text, List<Catalog.Offer> products, List<Knowledge.Evidence> citations) {
            this(type,text,products,citations,null,Map.of(),List.of());
        }
        /** 纯提示类回复：没有商品、引用、包数与合计。/ Message-only reply carrying no products, citations, quantity or totals. */
        static Reply message(String type, String text) { return new Reply(type,text,List.of(),List.of()); }
    }
    /**
     * 回答结果 / Answer result.
     *
     * @param filter 合并后的检索条件；调用方必须保存并在下一轮传回，缺失会导致追问上下文丢失。
     *               Merged filter; callers must store it and pass it back next turn, otherwise
     *               follow-up context is lost.
     * @param reply  本轮回复 / this turn's reply
     */
    public record Result(Filter filter, Reply reply) {}
    /** 商品目录；同时负责门店范围校验与品牌词表。/ Product catalog; also enforces store scoping and brand vocabulary. */
    private final Catalog catalog;
    /** 已审核资料的检索与可见性判断 / approved-source search and visibility checks. */
    private final Knowledge knowledge;
    /** 意图解析与证据选择，是流程中唯一的不确定性来源 / intent parsing and evidence selection, the only nondeterministic dependency. */
    private final LanguageModel model;
    /** 已知购买包数时执行价格/库存核验循环 / runs the price/stock verification loop when a pack count is known. */
    private final PurchasePlanner purchases;

    /**
     * 构造器注入依赖 / Constructor injection of collaborators.
     *
     * @param catalog   商品目录 / the product catalog
     * @param knowledge 审核资料索引 / the approved-source index
     * @param model     语言模型 / the language model
     * @param purchases 购买规划器 / the purchase planner
     */
    public Guide(Catalog catalog, Knowledge knowledge, LanguageModel model, PurchasePlanner purchases) {
        this.catalog = catalog;
        this.knowledge = knowledge;
        this.model = model;
        this.purchases = purchases;
    }

    /**
     * 回答一个问题，并返回新的可继承条件 / Answer one question and return the filter to carry forward.
     *
     * <p>处理顺序 / Pipeline:
     * <ol>
     *   <li>危急症状本地快路（不依赖模型）/ local red-flag fast path (no model call)</li>
     *   <li>识别问题中提到的品牌，供后续消歧 / detect brand mentions for later disambiguation</li>
     *   <li>模型解析出 {@link LanguageModel.Plan}，空意图直接 503 / parse the plan; null intent means 503</li>
     *   <li>安全意图、门店越权、知识问答三类分支 / safety, cross-store and knowledge branches</li>
     *   <li>槽位校验（包数、金额）后合并出新的 {@link Filter} / validate slots, then merge a new filter</li>
     *   <li>品牌与预算语义的追问 / clarify questions for brands and budget semantics</li>
     *   <li>已知包数走购买规划，否则做普通目录检索 / purchase planning when quantity is known, catalog lookup otherwise</li>
     * </ol>
     *
     * @param question 用户原话 / the raw user question
     * @param store    当前登录账号所属门店，所有数据访问都被限制在该门店内 /
     *                 store of the authenticated account; all data access is scoped to it
     * @param context 上一轮已确认的条件与等待导购回答的问题
     * @return 本轮回复 + 下一轮要继承的条件 / this turn's reply plus the filter for the next turn
     * @throws ResponseStatusException {@code 503} 模型输出不可用；库存故障由下层转为降级回复。
     *                                 503 when model output is unusable; stock faults are converted
     *                                 to a degraded reply below.
     */
    public Result answer(String question, Store store, LanguageModel.Context context) {
        Filter previous = context.conditions();
        // 危急症状走本地硬编码快路：不等模型、不改变已有条件，先保证安全。 / Red-flag symptoms short-circuit locally: no model wait, filter untouched.
        if (List.of("呼吸困难","抽搐","意识不清","严重脱水","喘不过气").stream().anyMatch(question::contains))
            return risk(previous);
        // 从目录词表里找出问题提到的品牌，稍后用于“要查还是排除”的消歧。 / Collect brand names from the catalog vocabulary; used later to disambiguate include vs exclude.
        var mentioned = catalog.mentionedBrands(store,question);
        var plan = model.interpret(question, store.id(), context, mentioned);
        // 模型只解析意图与槽位；空意图说明输出不可用，交给前端提示重试。/ The model only extracts intent and slots; a null intent is unusable output, so fail fast.
        if (plan.intent() == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型输出无效");
        if (plan.intent() == LanguageModel.Intent.RISK) return risk(previous);
        // 防止跨店查询：模型解析出的门店必须与登录账号所属门店一致。/ Block cross-store access: the planned store must match the authenticated store.
        if (plan.store() != null && !store.id().equals(plan.store()))
            return new Result(previous, Reply.message("access_denied","该门店超出当前账号的查询权限。"));
        if (plan.intent() == LanguageModel.Intent.KNOWLEDGE) {
            var evidence = knowledge.search(store.id(),question);
            if (!evidence.isEmpty()) {
                String id = model.selectEvidence(question, evidence);
                // 检索 → 模型选定 → 复核可见，三个条件同时成立才引用该条资料。/ Retrieve, model selection, and visibility re-check must all hold before citing.
                for (var hit : evidence) if (hit.id().equals(id) && knowledge.visible(store.id(),hit))
                    return new Result(previous,new Reply("knowledge",hit.excerpt(),List.of(),List.of(hit)));
            }
            // 检索为空、模型选了不存在的 id、或资料已不可见，统一降级为“资料不足”。/ Empty hits, unknown id or invisible evidence all degrade to the same reply.
            return new Result(previous,Reply.message("no_evidence","现有审核资料不足以回答这个问题。"));
        }
        // 意图不清或混购需求先追问目标商品，不进行任何检索。/ Ask for the target product when the intent is unclear or the basket mixes SKUs.
        if (plan.intent() == LanguageModel.Intent.OTHER)
            return new Result(previous,Reply.message("clarify","你想了解哪款商品，或查询哪方面的护理资料？"));
        if (plan.intent() == LanguageModel.Intent.MIXED_BASKET)
            return new Result(previous,Reply.message("clarify","当前支持同一款商品多包核算。先查哪一款、几包？"));
        // 包数必须是正整数。/ Pack count must be a positive integer.
        if (plan.quantity()!=null && plan.quantity()<1)
            return new Result(previous,Reply.message("clarify","请提供大于零的整数包数。"));
        if (invalidAmount(plan.budget()) || invalidAmount(plan.totalBudget()))
            return new Result(previous,Reply.message("clarify","请提供非负的预算金额，精确到分。"));
        // 合并历史条件与本轮槽位；即使后面追问，也要把部分条件带下去。/ Merge history with this turn's slots and carry partial conditions into clarifications.
        var filter = merge(previous, plan, question);
        var unresolved = new LinkedHashSet<>(mentioned);
        unresolved.removeAll(filter.brands());
        unresolved.removeAll(filter.excluded());
        if (plan.allow()!=null) unresolved.removeAll(plan.allow());
        // 问题里提到、但既没被确认查询也没被确认排除的品牌 → 必须追问。/ Brands mentioned but neither included nor excluded require an explicit answer.
        if (!unresolved.isEmpty())
            return new Result(filter,Reply.message("clarify","请确认要查询还是排除品牌“" + String.join("、",unresolved) + "”？"));
        // 金额语义不明（每包上限还是总预算）时先问清，绝不猜测。/ Ambiguous cap (per-pack limit vs total budget) is asked, never guessed.
        if (filter.pendingBudget()!=null)
            return new Result(filter,Reply.message("clarify",filter.pendingBudget()+" 元是每包上限，还是购买这些商品的总预算？"));
        // 有总预算却不知道买几包，无法核算。/ A total budget without a pack count cannot be verified.
        if (Boolean.TRUE.equals(filter.promotions()) && filter.quantity()==null)
            return new Result(filter,Reply.message("clarify","要核实本店活动，顾客想买同款几包？"));
        if (filter.totalBudget()!=null && filter.quantity()==null)
            return new Result(filter,Reply.message("clarify","按这个总预算，顾客想买同款几包？"));
        // 要推荐尺码但还没拿到尺码。/ Size recommendation requested without a size.
        if (plan.intent() == LanguageModel.Intent.RECOMMEND_SIZE && filter.size() == null)
            return new Result(filter,Reply.message("clarify","顾客需要什么尺码？可以直接告诉我包装上标的尺码。"));
        // 透气性等卖点常不在已审核资料里，回答时统一附上缺失说明。/ Attributes like breathability are often absent from approved data, so answers note the gap.
        String missing = question.contains("透气") ? "透气性" : plan.missingField();
        if (filter.quantity()!=null) {
            // 已知包数：先做价格/库存核验，再给出可购买结论。/ With a known pack count, verify price and stock before claiming availability.
            var reply = purchases.run(store, filter);
            if (!reply.products().isEmpty())
                reply = new Reply(reply.type(),describeMissing(reply.text(),missing),reply.products(),
                    reply.citations(),reply.quantity(),reply.totals(),reply.trace(),reply.quotes());
            return new Result(filter,reply);
        }
        try {
            // 未指定包数：只做轻量筛选（品类/尺码/品牌/预算/在库）。/ No pack count: lightweight filtering only (category/size/brand/budget/stock).
            var rows = catalog.query(store, store.id(), filter);
            if (rows.isEmpty()) return new Result(filter,Reply.message("empty","本店没有符合当前条件的商品。要调整预算或品牌吗？"));
            String message = "本店有 " + rows.size() + " 款商品符合当前筛选条件。";
            return new Result(filter,new Reply("products",describeMissing(message,missing),rows,List.of()));
        } catch (ResponseStatusException ex) {
            // 库存数据源故障降级为可重试提示；其它异常继续上抛，避免掩盖真实问题。/ A stock-source fault degrades to a retryable reply; other errors propagate.
            if (ex.getStatusCode()!=HttpStatus.SERVICE_UNAVAILABLE) throw ex;
            return new Result(filter,Reply.message("stock_unavailable","库存暂无法确认，请稍后重新查询。"));
        }
    }

    /**
     * 安全兜底回复 / Safety fallback reply.
     *
     * <p>保留原筛选条件，用户处理完风险后仍可继续刚才的查询。
     * The incoming filter is preserved so the shopping context survives after the risk is handled.
     *
     * @param filter 当前条件 / the current filter
     * @return {@code safety} 类型回复 / a reply of type {@code safety}
     */
    private static Result risk(Filter filter) {
        return new Result(filter,Reply.message("safety","请及时联系当地急救或前往医院就医。"));
    }

    /**
     * 在回复末尾补充“资料缺失”提示 / Append a "data missing" note to a reply.
     *
     * <p>只在确实缺少该字段时追加，不改变原有结论；字段为空则原样返回。
     * The note is appended only when the field is genuinely absent, never changing the
     * conclusion; a blank field returns the message unchanged.
     *
     * @param message 原始话术 / the original message
     * @param field   缺失的字段名，可能为 null / the missing field name, possibly null
     * @return 补充后的话术 / the annotated message
     */
    private static String describeMissing(String message, String field) {
        if (field==null || field.isBlank()) return message;
        return message + " 现有资料缺少可核实的“" + field + "”信息。";
    }

    /**
     * 合并历史条件与本轮模型解析结果 / Merge the carried filter with this turn's plan.
     *
     * <p>槽位优先级 / Slot precedence: 本轮显式值 > 历史值；{@code clear} 中的槽位被重置而不是继承。
     * This turn's explicit value beats history; slots listed in {@code clear} are reset
     * instead of inherited.
     *
     * <p>预算消歧是本方法最复杂的部分 / Budget disambiguation is the hard part:
     * <ol>
     *   <li>用措辞判断金额语义：“每包/单包/单价”为单价，“总预算/合计/总共…”为总价；两者同时出现且
     *       计划里给出 {@code totalBudget} 时，拆成“总预算 + 每包上限”。
     *       Wording decides the meaning: per-pack phrases mean a unit price, total phrases mean a
     *       total; when both appear and the plan carries a {@code totalBudget}, split them into a
     *       total cap plus a per-pack cap.</li>
     *   <li>本轮没有新金额时，用计划里的 {@code scope} 落实上一轮遗留的 {@code pendingBudget}。
     *       Without a new amount, resolve the previously pending cap using the plan's scope.</li>
     *   <li>仍无明确语义时按启发式落实：没买多包 → 单价；上一轮只有总预算 → 总价；上一轮只有单价 → 单价；
     *       都不满足就继续挂起，由 {@link #answer} 追问。
     *       Otherwise fall back to heuristics (single pack → unit price, previous total-only →
     *       total, previous unit-only → unit) and finally stay pending so {@link #answer} asks.</li>
     * </ol>
     *
     * @param old      上一轮继承的条件 / filter inherited from previous turns
     * @param p        本轮模型解析结果 / this turn's parsed plan
     * @param question 原始问题，用于关键词消歧 / the raw question used for wording heuristics
     * @return 新的条件对象；即使本轮只是追问也要保存 / the new filter; persist it even for clarifying turns
     */
    private static Filter merge(Filter old, LanguageModel.Plan p, String question) {
        // clear 是模型识别出的“重置”指令，例如用户说“不限品牌”。/ "clear" carries reset intents such as "no brand restriction".
        var clear = p.clear()==null ? Set.of() : p.clear();
        var excluded = new HashSet<>(old.excluded());
        if (clear.contains("exclude")) excluded.clear();
        // 允许的品牌从排除集合里移除；明确排除的加进去。/ Allowed brands leave the exclusion set; explicitly excluded ones join it.
        if (p.allow()!=null) excluded.removeAll(p.allow());
        if (p.exclude()!=null) excluded.addAll(p.exclude());
        Integer quantity = value(p.quantity(),old.quantity(),clear.contains("quantity"));
        BigDecimal unit = clear.contains("budget") ? null : old.budget();
        // 关键词启发式：模型给出的 scope 只在金额本身缺失时兜底。/ Keyword heuristics first; the plan's scope only fills gaps when no amount is given.
        boolean perPack = List.of("每包","单包","每一包","一包最多","单价").stream().anyMatch(question::contains);
        boolean combined = List.of("总预算","合计","总共","一共","总价","总额","总计","加起来","一起不超过","总花费","总支出")
            .stream().anyMatch(question::contains);
        LanguageModel.Scope scope = perPack ? LanguageModel.Scope.UNIT : null;
        if (combined && !perPack) scope = LanguageModel.Scope.TOTAL;
        // The first release treats an unspecified cap as a unit price. Only explicit wording
        // can introduce a new total cap; a follow-up can inherit an already confirmed scope.
        // 首发版本把未说明语义的金额视为单价；只有明确措辞才会引入新的总预算，追问可继承已确认的语义。
        BigDecimal total = clear.contains("totalBudget") ? null : old.totalBudget();
        BigDecimal pending = clear.contains("budget") || clear.contains("totalBudget") ? null : old.pendingBudget();
        BigDecimal amount = p.budget()!=null ? p.budget() : p.totalBudget();
        // 同时提到“每包”和“总预算”且计划给出总预算：拆分为两种上限后继续。/ Both phrases present with a total in the plan: split into two caps.
        if (combined && perPack && p.totalBudget()!=null) {
            total = p.totalBudget();
            amount = p.budget();
            pending = null;
        }
        // 本轮没给金额时，用已确认的语义落实上一轮挂起的金额。/ Resolve the pending amount with a confirmed scope when this turn adds no number.
        if (amount==null && pending!=null) {
            if (scope==null) scope=p.scope();
            if (scope!=null) amount=pending;
        }
        if (amount!=null) {
            pending=null;
            if (scope==LanguageModel.Scope.UNIT) unit=amount;
            else if (scope==LanguageModel.Scope.TOTAL) total=amount;
            // 没有明确语义时：未买多包按单价；沿用上一轮唯一确定的语义；否则挂起等追问。/ Without scope: single pack → unit; inherit an unambiguous prior meaning; else stay pending.
            else if (quantity==null || quantity==1) unit=amount;
            else if (old.totalBudget()!=null && old.budget()==null) total=amount;
            else if (old.budget()!=null && old.totalBudget()==null) unit=amount;
            else pending=amount;
        }
        return new Filter(value(p.category(),old.category(),clear.contains("category")),
            value(p.size(),old.size(),clear.contains("size")),unit,
            // inStock 未给出则继承；显式 clear 时回到 false。/ Inherit unless explicitly cleared, then default to false.
            p.inStock()!=null ? p.inStock() : old.inStock() && !clear.contains("inStock"),
            // 品牌集合同理：本轮优先，clear 置空，否则继承。/ Brands: this turn wins, clear empties them, otherwise inherit.
            p.brands()!=null ? p.brands() : clear.contains("brands") ? Set.of() : old.brands(), Set.copyOf(excluded),
            quantity,total,pending,p.promotions()!=null ? p.promotions() : old.promotions());
    }

    /**
     * 金额合法性校验 / Validate a budget amount.
     *
     * <p>负数、超过两位小数、或整数部分超过 12 位都判为无效，避免下游核算溢出或被误读。
     * Negative values, more than two decimals, or more than 12 integer digits are rejected to
     * avoid downstream overflow and misreading.
     *
     * @param amount 待校验金额，可能为 null / the amount to check, possibly null
     * @return 无效返回 {@code true} / {@code true} when invalid
     */
    private static boolean invalidAmount(BigDecimal amount) {
        return amount!=null && (amount.signum()<0 || amount.stripTrailingZeros().scale()>2 || amount.precision()-amount.scale()>12);
    }

    /**
     * 槽位合并原语 / Slot merge primitive.
     *
     * @param next     本轮值，{@code null} 表示未提及 / this turn's value; {@code null} means absent
     * @param previous 继承值 / the inherited value
     * @param clear    是否重置 / whether to reset
     * @param <T>      槽位类型 / slot type
     * @return 新值优先；否则按 clear 决定重置还是继承 / the new value, or reset/inherited accordingly
     */
    private static <T> T value(T next,T previous,boolean clear) {
        if (next!=null) return next;
        return clear ? null : previous;
    }
}