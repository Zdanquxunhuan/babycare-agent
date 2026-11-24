package com.yu.babycare.auth;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
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

    public static Store store(Authentication auth) {
        return STORES.get(auth.getName());
    }

    @Bean
    InMemoryUserDetailsManager accounts(@Value("${babycare.demo-password}") String password,
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
                .requestMatchers("/api/ops/**").hasRole("OPS")
                .requestMatchers("/api/**").hasRole("GUIDE")
                .anyRequest().authenticated())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,err) -> res.setStatus(401)))
            .formLogin(login -> login.loginProcessingUrl("/api/session/login")
                .successHandler((req,res,auth) -> {
                    req.getSession().removeAttribute(com.yu.babycare.conversation.Conversations.class.getName());
                    res.setStatus(204);
                })
                .failureHandler((req,res,err) -> res.setStatus(401)).permitAll())
            .logout(logout -> logout.logoutUrl("/api/session/logout")
                .logoutSuccessHandler((req,res,auth) -> res.setStatus(HttpStatus.NO_CONTENT.value())))
            .build();
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(Authentication auth, CsrfToken csrf) {
        if (auth == null) return Map.of("authenticated", false, "csrf", csrf.getToken());
        if (store(auth) == null) return Map.of("authenticated", true, "role", "OPS", "csrf", csrf.getToken());
        return Map.of("authenticated", true, "role", "GUIDE", "store", store(auth), "csrf", csrf.getToken());
    }
}
