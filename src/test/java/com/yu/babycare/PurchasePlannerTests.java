package com.yu.babycare;

import com.yu.babycare.agent.*;
import com.yu.babycare.auth.StoreSecurity.Store;
import com.yu.babycare.catalog.Catalog;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PurchasePlannerTests {
    private final Catalog catalog = mock(Catalog.class);
    private final LanguageModel model = mock(LanguageModel.class);
    private final Store store = new Store("STORE-01","直营");
    private final Catalog.Filter filter = new Catalog.Filter("纸尿裤","L",null,false,Set.of(),Set.of(),
        2,BigDecimal.valueOf(220),null,null);

    @Test void insufficientStepsStopBeforeNextTool() {
        when(catalog.search(store, filter)).thenReturn(List.of("DEMO-003"));
        var planner = new PurchasePlanner(catalog,mock(com.yu.babycare.catalog.Promotions.class),model,1,Duration.ofSeconds(3));
        assertEquals("planning_limit",planner.run(store,filter).type());
        verify(catalog).search(store,filter);
        verifyNoMoreInteractions(catalog);
        verifyNoInteractions(model);
    }

    @Test void expiredBudgetStopsBeforeSearch() {
        var planner = new PurchasePlanner(catalog,mock(com.yu.babycare.catalog.Promotions.class),model,4,Duration.ZERO);
        assertEquals("planning_limit",planner.run(store,filter).type());
        verifyNoInteractions(model,catalog);
    }
}
