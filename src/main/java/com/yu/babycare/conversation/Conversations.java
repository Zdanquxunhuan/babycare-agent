package com.yu.babycare.conversation;

import com.yu.babycare.agent.Guide;
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
        View view() { return new View(id, turns.size(), List.copyOf(turns)); }
    }
    private final Guide guide;
    public Conversations(Guide guide) { this.guide = guide; }

    @PostMapping
    public View create(HttpSession session) {
        Chat chat = new Chat();
        session.setAttribute(ATTRIBUTE,chat);
        return chat.view();
    }

    private Chat owned(HttpSession session, UUID id) {
        if (session.getAttribute(ATTRIBUTE) instanceof Chat chat && chat.id.equals(id)) return chat;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/{id}")
    public View read(HttpSession session,@PathVariable UUID id) {
        Chat chat = owned(session,id);
        synchronized (chat) { return chat.view(); }
    }

    @PostMapping("/{id}/messages")
    public View submit(Authentication auth, HttpSession session, @PathVariable UUID id, @RequestBody Input input) {
        Chat chat = owned(session,id);
        if (input.messageId()==null || input.text()==null || input.text().isBlank() || input.text().length()>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1 至 2000 字的问题");
        synchronized (chat) {
            for (Turn turn : chat.turns) if (turn.messageId().equals(input.messageId())) {
                if (turn.question().equals(input.text())) return chat.view();
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
            return chat.view();
        }
    }
}
