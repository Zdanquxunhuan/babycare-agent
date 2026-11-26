package com.yu.babycare.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class FixturePosReader implements PosReader {
    private final ObjectMapper json;
    private final Path file;

    FixturePosReader(ObjectMapper json,@Value("${babycare.stock-source}") Path file) {
        this.json=json;
        this.file=file;
    }

    public Map<String,Reading> read(String store,Set<String> skus) throws IOException {
        var rows = new HashMap<String,Reading>();
        try (var input = Files.newInputStream(file)) {
            var snapshot = json.readValue(input,Catalog.Fixture.class);
            String date = LocalDate.parse(snapshot.snapshot_date()).toString();
            for (var product : snapshot.products()) {
                if (!skus.contains(product.sku())) continue;
                for (var offer : product.store_offers()) {
                    if (!store.equals(offer.store_id())) continue;
                    if (offer.price_cny()==null || offer.price_cny().signum()<0 ||
                        offer.available_packs()==null || offer.available_packs()<0)
                        throw new IOException("门店价格或可售库存缺失或无效");
                    if (rows.put(product.sku(),new Reading(offer.price_cny(),offer.available_packs(),date,"模拟POS快照"))!=null)
                        throw new IOException("重复的门店商品");
                }
            }
        }
        if (!rows.keySet().equals(skus)) throw new IOException("缺少门店商品");
        return rows;
    }
}
