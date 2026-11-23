package com.yu.babycare.agent;

import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.knowledge.Knowledge.Evidence;
import java.util.List;
import java.util.Set;
import java.math.BigDecimal;

public interface LanguageModel {
    enum Intent { PRODUCT, RECOMMEND_SIZE, COMPARE, KNOWLEDGE, RISK, OTHER }
    record Plan(Intent intent, String store, String category, String size, BigDecimal budget,
                Boolean inStock, Set<String> brands, Set<String> exclude, Set<String> allow,
                Set<String> clear, String missingField) {}
    Plan interpret(String question, String store, Filter previous);
    String selectEvidence(String question, List<Evidence> evidence);
}
