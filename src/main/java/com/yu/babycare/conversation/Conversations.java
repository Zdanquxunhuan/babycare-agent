package com.yu.babycare.conversation;

import com.yu.babycare.agent.Guide;
import com.yu.babycare.knowledge.Policies;
import com.yu.babycare.knowledge.Knowledge;
import com.yu.babycare.auth.StoreSecurity;
import com.yu.babycare.catalog.Catalog.Filter;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 门店导购对话接口 / Store-guide conversation API.
 *
 * <p>对话状态放在当前 HTTP Session 中，因此一个浏览器会话同时只有一段进行中的对话，服务端不做持久化。
 * 登录成功时 {@link StoreSecurity} 会按同一个属性名清空旧对话，避免上一个账号的对话被下一个账号继承。
 * The chat lives in the HTTP session, so each browser session holds at most one in-progress
 * conversation and nothing is persisted server-side. The login success handler in
 * {@link StoreSecurity} removes this attribute so a previous account's chat cannot leak into
 * the next account's session.
 *
 * <p>并发模型：所有对对话的可变操作都在 {@code synchronized (chat)} 内完成，读接口返回不可变快照。
 * Concurrency: every mutation of a chat runs inside {@code synchronized (chat)}; read endpoints
 * return an immutable snapshot ({@link View}).
 *
 * <p>接口一览 / Endpoints:
 * <ul>
 *   <li>{@code POST /api/conversations} —— 新建对话 / start a new conversation</li>
 *   <li>{@code GET /api/conversations/{id}} —— 读取当前快照 / read the current snapshot</li>
 *   <li>{@code POST /api/conversations/{id}/messages} —— 提交问题并生成回答 / submit a question</li>
 *   <li>{@code POST /api/conversations/{id}/messages/{message}/feedback} —— 记录回答是否有用 /
 *       record whether an answer was helpful</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/conversations")
public class Conversations {
    /**
     * 客户端提交的问题 / A question submitted by the client.
     *
     * @param messageId 客户端生成的幂等键：网络重试沿用同一个 id，服务端不会重复生成回答。
     *                  Client-generated idempotency key: retries reuse the same id so the server
     *                  answers a question only once.
     * @param version   乐观并发版本号，取值为客户端已知的对话轮数（{@code turns.size()}）。
     *                  Optimistic-concurrency version: the number of turns the client has seen.
     * @param text      问题正文，允许 1–2000 字。/ The question text, 1–2000 characters.
     */
    public record Input(UUID messageId, int version, String text) {}

    /**
     * 一轮对话 / One conversation turn.
     *
     * @param messageId 该轮问题的幂等键 / idempotency key of this question
     * @param question  用户原话，保留原文便于展示 / the raw question text, kept verbatim for display
     * @param reply     导购 Agent 的回复，含引用证据 / the guide agent's reply, including citations
     */
    public record Turn(UUID messageId, String question, Guide.Reply reply) {}

    /**
     * 返回给前端的对话快照 / Conversation snapshot returned to the frontend.
     *
     * @param id      会话标识 / chat id
     * @param version 当前轮数，前端下次提交时回传为 {@link Input#version()} /
     *                current turn count, echoed back as {@link Input#version()} on the next submit
     * @param turns   已过滤掉失效引用的轮次 / turns whose expired citations have been redacted
     */
    public record View(UUID id, int version, List<Turn> turns) {}

    private static final Logger log = LoggerFactory.getLogger(Conversations.class);

    /**
     * Session 中存放对话的属性名 / Session attribute key holding the chat.
     *
     * <p>直接用类全名，既保证键唯一，又让登录处理器能在不引用本类实例的情况下按同一常量清理会话。
     * Using the fully-qualified class name keeps the key unique and lets the login handler purge
     * the chat via the same constant without depending on a bean instance.
     */
    private static final String ATTRIBUTE = Conversations.class.getName();

    /**
     * 一段进行中的对话 / One in-progress conversation.
     *
     * <p>只存在于当前 Session，不落库；进程重启或重新登录即丢失。
     * It only exists in the current session and is never persisted; a restart or re-login drops it.
     * <p>实例本身同时充当锁对象 / The instance doubles as the lock object.
     */
    private static class Chat {
        /** 会话标识，后续所有请求都带它 / Chat id carried by all follow-up requests. */
        final UUID id = UUID.randomUUID();
        /** 按时间顺序追加的轮次，最多 100 条 / turns appended in order, capped at 100. */
        final List<Turn> turns = new ArrayList<>();
        /**
         * 跨轮保留的检索条件（品类、尺码、预算、品牌等），使“那这款呢”之类的追问能继承上下文。
         * Retrieval filter carried across turns, so follow-ups like "what about this one?" keep context.
         */
        Filter filter = Filter.empty();

    }
    /** 回答问题并维护跨轮检索条件的导购 Agent / the guide agent that answers and maintains the filter. */
    private final Guide guide;
    /** 政策知识库；回答反馈也写入这里 / policy knowledge base, which also stores answer feedback. */
    private final Policies policies;
    /** 知识检索，用于读取时复核引用是否仍然可见 / knowledge search, used to re-check citation visibility on read. */
    private final Knowledge knowledge;

    /**
     * 构造器注入依赖 / Constructor injection of collaborators.
     *
     * @param guide     导购 Agent / the guide agent
     * @param policies  政策知识库 / the policy knowledge base
     * @param knowledge 知识检索 / the knowledge search index
     */
    public Conversations(Guide guide, Policies policies, Knowledge knowledge) {
        this.knowledge = knowledge;
        this.guide = guide;
        this.policies = policies;
    }

    /** 反馈载荷：{@code null} 表示客户端未明确表态，会被拒绝 / Feedback payload; {@code null} means "unspecified" and is rejected. */
    public record Feedback(Boolean helpful) {}

    /**
     * 记录“这条回答是否有用” / Record whether an answer was helpful.
     *
     * <p>只有当前 Session 自己的对话才能反馈；服务端用该轮的第一条引用作为证据锚点，幂等写入
     * {@code bc_answer_feedback}，重复提交会覆盖同一条记录（不新增行）。
     * Feedback is accepted only for the chat owned by this session. The first citation of the
     * turn is used as the evidence anchor and upserted into {@code bc_answer_feedback}, so
     * resubmitting overwrites the same row instead of inserting a duplicate.
     *
     * @throws ResponseStatusException 400 未给出 helpful；404 对话不属于当前 Session 或该轮没有引用。
     *                                 400 when {@code helpful} is missing; 404 when the chat is
     *                                 not owned by this session or the turn cites no evidence.
     */
    @PostMapping("/{id}/messages/{message}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(Authentication auth, HttpSession session, @PathVariable UUID id,
                         @PathVariable UUID message, @RequestBody Feedback feedback) {
        // 必须明确表达有用/没用，缺省值不做猜测。 / Reject an unspecified verdict rather than guessing.
        if (feedback.helpful()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Chat chat = owned(session,id);
        synchronized (chat) {
            // 一轮回答可能引用多条政策，反馈只挂在第一条证据上。 / A turn may cite several policies; anchor feedback to the first.
            var evidence = chat.turns.stream().filter(turn -> turn.messageId().equals(message))
                .flatMap(turn -> turn.reply().citations().stream()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            policies.feedback(auth.getName(),message,evidence,feedback.helpful());
        }
    }

    /**
     * 生成前端可见的对话快照 / Build the snapshot visible to the frontend.
     *
     * <p>每次读取都重新校验引用可见性：政策被撤回、过期或切换到其它门店后，旧回答会被替换为
     * “原引用资料已失效”的提示，而不是继续展示导购已无权查看的资料。轮次本身不删除，保证
     * 版本号（轮数）稳定。
     * Citation visibility is re-checked on every read: once a policy is withdrawn, expires, or the
     * store changes, the old reply is replaced with an "evidence expired" notice instead of showing
     * material the user may no longer see. Turns are kept so the version (turn count) stays stable.
     *
     * <p>调用方必须持有 chat 锁 / The caller must hold the chat lock.
     */
    private View view(Chat chat, Authentication auth) {
        var store = StoreSecurity.store(auth);
        var visible = chat.turns.stream().map(turn -> {
            // 所有引用都可见才原样返回，任一失效即整轮降级为 no_evidence。/ All citations must still be visible, otherwise redact the reply.
            if (turn.reply().citations().stream().allMatch(c -> knowledge.visible(store.id(),c))) return turn;
            return new Turn(turn.messageId(),turn.question(),
                new Guide.Reply("no_evidence","原引用资料已失效，请重新提问。",List.of(),List.of(),null,Map.of(),List.of()));
        }).toList();
        return new View(chat.id,chat.turns.size(),visible);
    }

    /**
     * 新建对话，并覆盖当前 Session 里已有的对话 / Start a new chat, replacing any chat in this session.
     *
     * <p>Session 本身就是归属凭证，所以不需要额外的会话表。
     * The session acts as the ownership token, so no separate chat table is needed.
     */
    @PostMapping
    public View create(Authentication auth,HttpSession session) {
        Chat chat = new Chat();
        session.setAttribute(ATTRIBUTE,chat);
        return view(chat,auth);
    }

    /**
     * 取回当前 Session 名下的对话 / Fetch the chat owned by this session.
     *
     * <p>id 不匹配时返回 404 而非 403，避免暴露某个 id 是否真实存在。
     * A mismatched id yields 404 rather than 403 so the API does not reveal whether an id exists.
     */
    private Chat owned(HttpSession session, UUID id) {
        if (session.getAttribute(ATTRIBUTE) instanceof Chat chat && chat.id.equals(id)) return chat;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /**
     * 读取对话快照 / Read the current snapshot.
     *
     * <p>加锁保证不会读到提交过程中的半成品状态。
     * The lock prevents observing a partially applied submit.
     */
    @GetMapping("/{id}")
    public View read(Authentication auth,HttpSession session,@PathVariable UUID id) {
        Chat chat = owned(session,id);
        synchronized (chat) { return view(chat,auth); }
    }

    /**
     * 提交问题并追加一轮回答 / Submit a question and append one answered turn.
     *
     * <p>处理顺序 / Order of checks:
     * <ol>
     *   <li>会话归属 / chat ownership</li>
     *   <li>入参校验：非空且不超过 2000 字 / input validation: non-blank and at most 2000 chars</li>
     *   <li>幂等：同一 messageId 重放，内容一致则直接返回快照，内容不一致则 409 /
     *       idempotency: replaying the same messageId returns the snapshot when the text matches,
     *       otherwise 409</li>
     *   <li>乐观并发：version 必须等于当前轮数 / optimistic concurrency: version must equal the turn count</li>
     *   <li>上限 100 轮，超出提示新建对话 / cap at 100 turns and ask the user to start a new chat</li>
     *   <li>调用 Agent、追加轮次、推进跨轮 filter / call the agent, append the turn, advance the filter</li>
     * </ol>
     *
     * @throws ResponseStatusException 400 入参非法或超过轮数上限；404 对话不属于当前 Session；
     *                                 409 messageId 重放内容不一致或 version 已过期。
     *                                 400 for invalid input or too many turns; 404 when the chat is
     *                                 not owned; 409 on an idempotency conflict or a stale version.
     */
    @PostMapping("/{id}/messages")
    public View submit(Authentication auth, HttpSession session, @PathVariable UUID id, @RequestBody Input input) {
        Chat chat = owned(session,id);
        if (input.messageId()==null || input.text()==null || input.text().isBlank() || input.text().length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1 至 2000 字的问题");
        synchronized (chat) {
            // 先查重：网络重试不应追加第二轮相同回答。/ Deduplicate first: a retry must not create a second identical turn.
            for (Turn turn : chat.turns) if (turn.messageId().equals(input.messageId())) {
                if (turn.question().equals(input.text())) return view(chat,auth);
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            // 客户端基于旧快照提交（例如另一个标签页已追加轮次），拒绝而不是覆盖。/ Client is on a stale snapshot (e.g. another tab appended); reject instead of overwriting.
            if (input.version()!=chat.turns.size()) throw new ResponseStatusException(HttpStatus.CONFLICT);
            if (chat.turns.size()>=100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请新建对话");
            var store = StoreSecurity.store(auth);
            // 记录耗时，便于结合下面的日志观察模型与检索延迟。/ Capture latency for the log line below.
            long start = System.nanoTime();
            var result = guide.answer(input.text(),store,chat.filter);
            chat.turns.add(new Turn(input.messageId(),input.text(),result.reply()));
            // filter 由 Agent 依据历史与本轮推导，写回后下一轮可直接继承上下文。/ The agent derives it from history; persisting it lets the next turn inherit context.
            chat.filter = result.filter();
            log.info("turn={} store={} result={} durationMs={} evidence={}", input.messageId(), store.id(),
                result.reply().type(), (System.nanoTime()-start)/1_000_000,
                result.reply().citations().stream().map(c -> c.id()).toList());
            return view(chat,auth);
        }
    }
}