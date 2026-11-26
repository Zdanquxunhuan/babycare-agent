package com.yu.babycare.catalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Store-scoped ordinary CNY price per pack and directly reported sellable packs, from one snapshot. */
public interface PosReader {
    record Reading(BigDecimal price, int availablePacks, String businessTime, String source) {}
    Map<String,Reading> read(String store, Set<String> skus) throws IOException;
}
