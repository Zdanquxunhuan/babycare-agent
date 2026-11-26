package com.yu.babycare.conversation;

import com.yu.babycare.agent.Guide;
import com.yu.babycare.knowledge.Policies;
import com.yu.babycare.knowledge.Knowledge;
import com.yu.babycare.auth.StoreSecurity;
import org.springframework.transaction.annotation.Transactional;
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
 * <p>按当前导购和门店读取数据库检查点，模型调用在检查点事务之外执行。
 * POST /current resumes the owner's task, while POST / explicitly starts a new one.
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
    public record View(UUID id, int version, List<Turn> turns,List<AftersalesReviews.Ticket> reviews) {}

    private static final Logger log = LoggerFactory.getLogger(Conversations.class);

    private final Checkpoints checkpoints;
    private final AftersalesReviews reviews;
    private final Guide guide;
    private final Policies policies;
    private final Knowledge knowledge;

    public Conversations(Checkpoints checkpoints, Guide guide, Policies policies, Knowledge knowledge, AftersalesReviews reviews) {
        this.reviews = reviews;
        this.checkpoints = checkpoints;
        this.guide = guide;
        this.policies = policies;
        this.knowledge = knowledge;
    }

    public record Feedback(Boolean helpful) {}

    @PostMapping("/{id}/messages/{message}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void feedback(Authentication auth, @PathVariable UUID id,
                         @PathVariable UUID message, @RequestBody Feedback feedback) {
        if (feedback.helpful()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var owner = checkpoints.owner(auth);
        var evidence = checkpoints.read(owner,id).turns().stream().filter(t -> t.messageId().equals(message))
            .flatMap(t -> t.reply().citations().stream()).filter(e -> knowledge.visible(owner.store().id(),e))
            .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        policies.feedback(owner.guide(),message,evidence,feedback.helpful());
    }

    /** Recheck the visibility of historical citations before displaying a stored response. */
    private View view(Checkpoints.Snapshot chat, StoreSecurity.Store store) {
        var visible = chat.turns().stream().map(turn -> {
            if (turn.reply().citations().stream().allMatch(c -> knowledge.visible(store.id(),c))) return turn;
            if (turn.reply().policyText()!=null)
                return new Turn(turn.messageId(),turn.question(),turn.reply().withPolicy(java.util.Optional.empty()));
            return new Turn(turn.messageId(),turn.question(),
                new Guide.Reply("no_evidence","原引用资料已失效，请重新提问。",List.of(),List.of(),null,Map.of(),List.of()));
        }).toList();
        return new View(chat.id(),chat.version(),visible,reviews.forChat(chat.id(),store.id()));
    }

    @PostMapping
    public View create(Authentication auth) {
        var owner = checkpoints.owner(auth);
        return view(checkpoints.open(owner,true),owner.store());
    }

    @PostMapping("/current")
    public View resume(Authentication auth) {
        var owner = checkpoints.owner(auth);
        return view(checkpoints.open(owner,false),owner.store());
    }

    @GetMapping("/{id}")
    public View read(Authentication auth,@PathVariable UUID id) {
        var owner = checkpoints.owner(auth);
        return view(checkpoints.read(owner,id),owner.store());
    }

    @PostMapping("/{id}/messages")
    public View submit(Authentication auth, @PathVariable UUID id, @RequestBody Input input) {
        var owner = checkpoints.owner(auth);
        if (input.messageId()==null || input.text()==null || input.text().isBlank() || input.text().length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入 1 至 2000 字的问题");
        var claim = checkpoints.claim(owner,id,input);
        if (claim.token()!=null) {
            long start = System.nanoTime();
            try (var correlation = org.slf4j.MDC.putCloseable("messageId", input.messageId().toString())) {
                var result = guide.answer(input.text(),owner.store(),claim.context());
                if ("review_draft".equals(result.reply().type()))
                    result = reviews.prepare(result,checkpoints.read(owner,id),input.text(),owner.store().id());
                checkpoints.finish(checkpoints.owner(auth),id,input,claim,result);
                log.info("turn={} chat={} guide={} store={} result={} durationMs={} evidence={}",
                    input.messageId(),id,owner.guide(),owner.store().id(),
                    result.reply().type(),(System.nanoTime()-start)/1_000_000,
                    result.reply().citations().stream().map(c -> c.id()).toList());
            } catch (RuntimeException ex) {
                checkpoints.release(id,claim.token());
                throw ex;
            }
        }
        return read(auth,id);
    }
}
