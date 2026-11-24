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

@RestController
@RequestMapping("/api/conversations")
public class Conversations {
    public record Input(UUID messageId, int version, String text) {}
    public record Turn(UUID messageId, String question, Guide.Reply reply) {}
    public record View(UUID id, int version, List<Turn> turns) {}
    private static final Logger log = LoggerFactory.getLogger(Conversations.class);
    private static final String ATTRIBUTE = Conversations.class.getName();
    private static class Chat {
        final UUID id = UUID.randomUUID();
        final List<Turn> turns = new ArrayList<>();
        Filter filter = Filter.empty();

    }
    private final Guide guide;
    private final Policies policies;
    private final Knowledge knowledge;
    public Conversations(Guide guide, Policies policies, Knowledge knowledge) {
        this.knowledge = knowledge;
        this.guide = guide;
        this.policies = policies;
    }

    public record Feedback(Boolean helpful) {}
    @PostMapping("/{id}/messages/{message}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(Authentication auth, HttpSession session, @PathVariable UUID id,
                         @PathVariable UUID message, @RequestBody Feedback feedback) {
        if (feedback.helpful()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Chat chat = owned(session,id);
        synchronized (chat) {
            var evidence = chat.turns.stream().filter(turn -> turn.messageId().equals(message))
                .flatMap(turn -> turn.reply().citations().stream()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            policies.feedback(auth.getName(),message,evidence,feedback.helpful());
        }
    }

    private View view(Chat chat, Authentication auth) {
        var store = StoreSecurity.store(auth);
        var visible = chat.turns.stream().map(turn -> {
            if (turn.reply().citations().stream().allMatch(c -> knowledge.visible(store.id(),c))) return turn;
            return new Turn(turn.messageId(),turn.question(),
                new Guide.Reply("no_evidence","原引用资料已失效，请重新提问。",List.of(),List.of(),null,Map.of(),List.of()));
        }).toList();
        return new View(chat.id,chat.turns.size(),visible);
    }

    @PostMapping
    public View create(Authentication auth,HttpSession session) {
        Chat chat = new Chat();
        session.setAttribute(ATTRIBUTE,chat);
        return view(chat,auth);
    }

    private Chat owned(HttpSession session, UUID id) {
        if (session.getAttribute(ATTRIBUTE) instanceof Chat chat && chat.id.equals(id)) return chat;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/{id}")
    public View read(Authentication auth,HttpSession session,@PathVariable UUID id) {
        Chat chat = owned(session,id);
        synchronized (chat) { return view(chat,auth); }
    }

    @PostMapping("/{id}/messages")
    public View submit(Authentication auth, HttpSession session, @PathVariable UUID id, @RequestBody Input input) {
        Chat chat = owned(session,id);
        if (input.messageId()==null || input.text()==null || input.text().isBlank() || input.text().length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1 至 2000 字的问题");
        synchronized (chat) {
            for (Turn turn : chat.turns) if (turn.messageId().equals(input.messageId())) {
                if (turn.question().equals(input.text())) return view(chat,auth);
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            if (input.version()!=chat.turns.size()) throw new ResponseStatusException(HttpStatus.CONFLICT);
            if (chat.turns.size()>=100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请新建对话");
            var store = StoreSecurity.store(auth);
            long start = System.nanoTime();
            var result = guide.answer(input.text(),store,chat.filter);
            chat.turns.add(new Turn(input.messageId(),input.text(),result.reply()));
            chat.filter = result.filter();
            log.info("turn={} store={} result={} durationMs={} evidence={}", input.messageId(), store.id(),
                result.reply().type(), (System.nanoTime()-start)/1_000_000,
                result.reply().citations().stream().map(c -> c.id()).toList());
            return view(chat,auth);
        }
    }
}
