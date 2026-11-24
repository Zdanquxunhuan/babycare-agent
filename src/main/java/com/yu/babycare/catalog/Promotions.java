package com.yu.babycare.catalog;

import com.yu.babycare.auth.StoreSecurity.Store;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Read-only, single-SKU offer. A qualifying purchase receives the reduction once. */
@Service
public class Promotions {
    public record Rule(String title, String storeId, String sku, LocalDate startsOn, LocalDate endsOn,
                       int minimumQuantity, BigDecimal discount) {
        public Rule {
            if (title == null || title.isBlank() || storeId == null || storeId.isBlank() || sku == null || sku.isBlank()
                    || startsOn == null || endsOn == null || endsOn.isBefore(startsOn)
                    || minimumQuantity < 1 || discount == null || discount.signum() <= 0)
                throw new IllegalArgumentException("活动样本无效");
        }

        public boolean applies(Store store, Collection<String> candidates, int quantity, LocalDate today) {
            return storeId.equals(store.id()) && candidates.contains(sku) && quantity >= minimumQuantity
                && !today.isBefore(startsOn) && !today.isAfter(endsOn);
        }
    }

    private final Rule rule;
    private final Path fault;
    private final Clock clock;
    public Promotions(Clock clock, ObjectMapper mapper, @Value("${babycare.fixtures:fixtures}") Path fixtures,
                      @Value("${babycare.promotion-fault-file:}") String flag) {
        this.clock = clock;
        rule = mapper.readValue(fixtures.resolve("promotion-demo.json").toFile(), Rule.class);
        fault = flag.isBlank() ? null : Path.of(flag);
    }

    public Optional<Rule> lookup(Store store, Collection<String> candidates, int quantity) {
        if (quantity < 1 || candidates.isEmpty()) throw new IllegalArgumentException("无效活动查询");
        if (fault != null && Files.exists(fault))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"活动查询暂不可用");
        var today = LocalDate.now(clock);
        return rule.applies(store,candidates,quantity,today) ? Optional.of(rule) : Optional.empty();
    }
}
