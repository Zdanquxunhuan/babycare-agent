package com.yu.babycare.agent;

import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.knowledge.Knowledge.Evidence;
import java.util.List;
import java.util.Set;
import java.math.BigDecimal;

public interface LanguageModel {
    enum Intent { PRODUCT, RECOMMEND_SIZE, COMPARE, MIXED_BASKET, KNOWLEDGE, RISK, OTHER }
    enum Scope { UNIT, TOTAL }
    enum Check { PRICE, STOCK }
    record Plan(Intent intent, String store, String category, String size, BigDecimal budget,
                Boolean inStock, Set<String> brands, Set<String> exclude, Set<String> allow,
                Set<String> clear, String missingField, Integer quantity, BigDecimal totalBudget, Scope scope) {
        public Plan(Intent intent, String store, String category, String size, BigDecimal budget,
                    Boolean inStock, Set<String> brands, Set<String> exclude, Set<String> allow,
                    Set<String> clear, String missingField) {
            this(intent, store, category, size, budget, inStock, brands, exclude, allow, clear, missingField,
                null, null, null);
        }
    }
    record Context(Filter conditions, String pendingQuestion) {}
    Plan interpret(String question, String store, Context context, Set<String> mentionedBrands);
    String selectEvidence(String question, List<Evidence> evidence);
    Check firstCheck(Filter conditions, int candidates, long remainingMillis);
}
