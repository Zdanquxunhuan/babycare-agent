package com.yu.babycare;

import com.yu.babycare.agent.*;
import com.yu.babycare.auth.StoreSecurity.Store;
import com.yu.babycare.catalog.Catalog;
import com.yu.babycare.catalog.Promotions;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PurchasePlannerTests {
    private final Catalog catalog = mock(Catalog.class);
    private final Promotions promotions = mock(Promotions.class);
    private final LanguageModel model = mock(LanguageModel.class);
    private final Store store = new Store("STORE-01","直营");
    private final Catalog.Filter filter = new Catalog.Filter("纸尿裤","L",null,false,Set.of(),Set.of(),
        2,BigDecimal.valueOf(220),null,null);

    @Test void insufficientStepsStopBeforeNextTool() {
        when(catalog.search(store, filter)).thenReturn(List.of("DEMO-003"));
        var planner = new PurchasePlanner(catalog,promotions,model,1,Duration.ofSeconds(3));
        assertEquals("planning_limit",planner.run(store,filter).type());
        verify(catalog).search(store,filter);
        verifyNoMoreInteractions(catalog);
        verifyNoInteractions(model);
    }

    @Test void stockFirstAvoidsUnnecessaryPriceAndPromotionCalls() {
        var requested = new Catalog.Filter("纸尿裤","L",null,false,Set.of("绒朵"),Set.of(),4,
            BigDecimal.valueOf(500),null,true);
        var skus = List.of("DEMO-008");
        var offer = new Catalog.Offer("DEMO-008","绒朵 L 码","绒朵","纸尿裤","L",52,
            BigDecimal.valueOf(119),3,"模拟",java.time.Instant.EPOCH);
        when(catalog.search(store,requested)).thenReturn(skus);
        when(catalog.check(eq(store),eq(skus),anyBoolean(),anyLong())).thenReturn(List.of(offer));
        var planner = new PurchasePlanner(catalog,promotions,model,5,Duration.ofSeconds(3));

        // Same candidate and stock, two controlled tool orders through the same executor.
        when(model.firstCheck(eq(requested),eq(1),anyLong()))
            .thenReturn(LanguageModel.Check.PROMOTION,LanguageModel.Check.STOCK);
        var promoFirst = planner.run(store,requested);
        assertEquals("empty",promoFirst.type());
        verify(promotions).lookup(store,skus,4);
        verify(catalog).check(eq(store),eq(skus),eq(false),anyLong());
        verify(catalog).check(eq(store),eq(skus),eq(true),anyLong());
        clearInvocations(catalog,promotions);
        var stockFirst = planner.run(store,requested);
        assertEquals(promoFirst.text(),stockFirst.text());
        verify(catalog).search(store,requested);
        verify(catalog).check(eq(store),eq(skus),eq(true),anyLong());
        verifyNoMoreInteractions(catalog,promotions);
    }

    @Test void priceAfterStockKeepsVerifiedQuantity() {
        var skus = List.of("DEMO-008");
        var price = BigDecimal.valueOf(100);
        var stale = new Catalog.Offer("DEMO-008","绒朵","绒朵","纸尿裤","L",52,price,3,"模拟",java.time.Instant.EPOCH);
        var current = new Catalog.Offer("DEMO-008","绒朵","绒朵","纸尿裤","L",52,price,5,"模拟",java.time.Instant.now());
        when(catalog.search(store,filter)).thenReturn(skus);
        when(model.firstCheck(eq(filter),eq(1),anyLong())).thenReturn(LanguageModel.Check.STOCK);
        when(catalog.check(eq(store),eq(skus),eq(true),anyLong())).thenReturn(List.of(current));
        when(catalog.check(eq(store),eq(skus),eq(false),anyLong())).thenReturn(List.of(stale));
        var planner = new PurchasePlanner(catalog,promotions,model,5,Duration.ofSeconds(3));
        assertEquals(5,planner.run(store,filter).products().getFirst().packs());
    }

    @Test void modelCannotCallPromotionForAnOrdinaryPurchase() {
        when(catalog.search(store,filter)).thenReturn(List.of("DEMO-008"));
        when(model.firstCheck(eq(filter),eq(1),anyLong())).thenReturn(LanguageModel.Check.PROMOTION);
        var planner = new PurchasePlanner(catalog,promotions,model,5,Duration.ofSeconds(3));
        assertEquals("planning_failed",planner.run(store,filter).type());
        verifyNoInteractions(promotions);
    }

    @Test void expiredBudgetStopsBeforeSearch() {
        var planner = new PurchasePlanner(catalog,promotions,model,4,Duration.ZERO);
        assertEquals("planning_limit",planner.run(store,filter).type());
        verifyNoInteractions(model,catalog);
    }
}
