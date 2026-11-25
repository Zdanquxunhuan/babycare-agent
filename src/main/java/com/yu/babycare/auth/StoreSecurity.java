package com.yu.babycare.auth;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Configuration
@RestController
public class StoreSecurity {
    public record Store(String id, String name) {}
    private static final Map<String, Store> STORES = Map.of(
        "direct", new Store("STORE-01", "模拟直营店"),
        "franchise", new Store("STORE-02", "模拟加盟店"));

    // Explicit identifiers for the two demo stores. Semantic references are handled by the planner.
    private static final Map<String, Pattern> STORE_MENTIONS = Map.of(
        "STORE-01", Pattern.compile("直营店|(?<![A-Z0-9])(?:A\\s*店|STORE-01(?![A-Z0-9]))", Pattern.CASE_INSENSITIVE),
        "STORE-02", Pattern.compile("加盟店|(?<![A-Z0-9])(?:B\\s*店|STORE-02(?![A-Z0-9]))", Pattern.CASE_INSENSITIVE));

    public static boolean mentionsOtherStore(Store store, String question) {
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFKC);
        return STORE_MENTIONS.entrySet().stream().anyMatch(entry ->
            !entry.getKey().equals(store.id()) && entry.getValue().matcher(normalized).find());
    }

    private final UserDetailsService accounts;

    public StoreSecurity(UserDetailsService accounts) {
        this.accounts = accounts;
    }

    public boolean hasCurrentRole(Authentication auth, String role) {
        if (auth == null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(role))) return false;
        if ("ROLE_GUIDE".equals(role) && store(auth) == null) return false;
        try {
            var user = accounts.loadUserByUsername(auth.getName());
            return user.isEnabled() && user.isAccountNonLocked() && user.isAccountNonExpired()
                && user.isCredentialsNonExpired() && user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role));
        } catch (UsernameNotFoundException revoked) {
            return false;
        }
    }

    public static List<Store> stores() {
        return STORES.values().stream().distinct().sorted(Comparator.comparing(Store::id)).toList();
    }

    public static Store store(Authentication auth) {
        return STORES.get(auth.getName());
    }

    @Bean
    static InMemoryUserDetailsManager accounts(@Value("${babycare.demo-password}") String password,
            @Value("${babycare.ops-password:}") String opsPassword) {
        var encoder = new BCryptPasswordEncoder();
        var users = STORES.keySet().stream().map(name -> User.withUsername(name)
            .password("{bcrypt}" + encoder.encode(password)).roles("GUIDE").build()).toList();
        var manager = new InMemoryUserDetailsManager(users);
        if (!opsPassword.isBlank()) {
            manager.createUser(User.withUsername("ops").password("{bcrypt}" + encoder.encode(opsPassword))
                .roles("OPS").build());
        }
        return manager;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(rules -> rules
                .requestMatchers("/", "/index.html", "/app.js", "/style.css", "/api/session", "/error").permitAll()
                .requestMatchers("/api/ops/**").access((auth, context) ->
                    new AuthorizationDecision(hasCurrentRole(auth.get(), "ROLE_OPS")))
                .requestMatchers("/api/**").access((auth, context) ->
                    new AuthorizationDecision(hasCurrentRole(auth.get(), "ROLE_GUIDE")))
                .anyRequest().authenticated())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,err) -> res.setStatus(401)))
            .formLogin(login -> login.loginProcessingUrl("/api/session/login")
                .successHandler((req,res,auth) -> res.setStatus(204))
                .failureHandler((req,res,err) -> res.setStatus(401)).permitAll())
            .logout(logout -> logout.logoutUrl("/api/session/logout")
                .logoutSuccessHandler((req,res,auth) -> res.setStatus(HttpStatus.NO_CONTENT.value())))
            .build();
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(Authentication auth, CsrfToken csrf) {
        if (auth == null) return Map.of("authenticated", false, "csrf", csrf.getToken());
        if (hasCurrentRole(auth, "ROLE_OPS")) return Map.of("authenticated", true, "role", "OPS", "csrf", csrf.getToken());
        if (!hasCurrentRole(auth, "ROLE_GUIDE")) return Map.of("authenticated", false, "csrf", csrf.getToken());
        return Map.of("authenticated", true, "role", "GUIDE", "store", store(auth), "csrf", csrf.getToken());
    }
}
