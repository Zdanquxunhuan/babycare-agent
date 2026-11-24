package com.yu.babycare.knowledge;

import com.yu.babycare.auth.StoreSecurity;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PolicyController {
    private final Policies policies;
    public PolicyController(Policies policies) { this.policies = policies; }

    @GetMapping("/api/ops/policies")
    public List<Policies.Version> list() { return policies.versions(); }

    @PostMapping("/api/ops/policies")
    public Map<String,Integer> upload(@RequestBody Policies.Draft draft) {
        return Map.of("version",policies.upload(draft));
    }

    @GetMapping(value="/api/ops/policies/{key}/{rev}", produces="text/plain;charset=UTF-8")
    public String original(@PathVariable String key,@PathVariable int rev) {
        return policies.original(key,rev);
    }

    @PostMapping("/api/ops/policies/{key}/{rev}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@PathVariable String key,@PathVariable int rev,Authentication auth) {
        policies.publish(key,rev,auth.getName());
    }

    @PostMapping("/api/ops/policies/{key}/{rev}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable String key,@PathVariable int rev) {
        policies.withdraw(key,rev);
    }

    @GetMapping(value="/api/knowledge/citations/{id}", produces="text/plain;charset=UTF-8")
    public String citation(@PathVariable String id,Authentication auth) {
        return policies.citation(StoreSecurity.store(auth).id(),id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
