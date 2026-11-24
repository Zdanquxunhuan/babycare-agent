package com.yu.babycare;

import com.yu.babycare.auth.StoreSecurity.Store;
import com.yu.babycare.catalog.Promotions.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromotionRuleTests {
    @Test void inclusiveDatesStoreSkuAndThreshold() {
        var start = LocalDate.of(2026,11,23);
        var end = start.plusDays(2);
        var rule = new Rule("满两包减20","STORE-01","DEMO-008",start,end,2,BigDecimal.valueOf(20));
        var store = new Store("STORE-01","直营");
        var skus = List.of("DEMO-008");
        assertTrue(rule.applies(store,skus,2,start));
        assertTrue(rule.applies(store,skus,3,end));
        assertFalse(rule.applies(store,skus,2,end.plusDays(1)));
        assertFalse(rule.applies(store,skus,2,start.minusDays(1)));
        assertFalse(rule.applies(store,skus,1,start));
        assertFalse(rule.applies(store,List.of("DEMO-003"),2,start));
        assertFalse(rule.applies(new Store("STORE-02","加盟"),skus,2,start));
    }
}
