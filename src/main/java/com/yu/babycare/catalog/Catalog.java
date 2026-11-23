package com.yu.babycare.catalog;

import com.yu.babycare.auth.StoreSecurity;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class Catalog {
    record Price(String store_id, BigDecimal price_cny, int available_packs) {}
    record Product(String sku, String name, String brand, String category, String size, int pieces_per_pack,
                   List<Price> store_offers) {}
    record Fixture(boolean simulated, String description, String snapshot_date, List<StoreSecurity.Store> stores,
                   List<Product> products) {}
    public record Offer(String sku, String name, String brand, String category, String size, int pieces,
                        BigDecimal price, int packs, String snapshot, Instant queriedAt) {}
    public record Filter(String category, String size, BigDecimal budget, boolean inStock,
                         Set<String> brands, Set<String> excluded) {
        public static Filter empty() { return new Filter(null, null, null, false, Set.of(), Set.of()); }
        boolean matches(Offer row) {
            return (category == null || category.equals(row.category()))
                && (size == null || size.equalsIgnoreCase(row.size()))
                && (budget == null || row.price().compareTo(budget) <= 0)
                && (!inStock || row.packs() > 0)
                && (brands.isEmpty() || brands.contains(row.brand()))
                && !excluded.contains(row.brand());
        }
    }

    private final Fixture fixture;
    private final Path stockFault;
    public Catalog(ObjectMapper mapper, @Value("${babycare.fixtures:fixtures}") Path dir,
                   @Value("${babycare.stock-fault-file:}") String faultFile) {
        fixture = mapper.readValue(dir.resolve("catalog-demo.json").toFile(), Fixture.class);
        stockFault = faultFile.isBlank() ? null : Path.of(faultFile);
        var ids = new HashSet<String>();
        for (Product p : fixture.products()) {
            if (!ids.add(p.sku()) || p.store_offers().stream().anyMatch(o -> o.price_cny().signum()<0
                    || o.available_packs()<0 || fixture.stores().stream().noneMatch(s -> s.id().equals(o.store_id()))))
                throw new IllegalStateException("商品样本字段无效 " + p.sku());
        }
    }

    public List<Offer> query(StoreSecurity.Store identity, String requestedStore, Filter filter) {
        if (!identity.id().equals(requestedStore)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (stockFault != null && java.nio.file.Files.exists(stockFault))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "库存暂无法确认");
        Instant now = Instant.now();
        return fixture.products().stream().flatMap(p -> p.store_offers().stream()
            .filter(o -> requestedStore.equals(o.store_id()))
            .map(o -> new Offer(p.sku(), p.name(), p.brand(), p.category(), p.size(), p.pieces_per_pack(),
                o.price_cny(), o.available_packs(), fixture.snapshot_date(), now)))
            .filter(filter::matches).toList();
    }

    @GetMapping("/api/stores/{storeId}/offers/{skuId}")
    public Offer offer(Authentication auth, @PathVariable String storeId, @PathVariable String skuId) {
        return query(StoreSecurity.store(auth), storeId, Filter.empty()).stream()
            .filter(row -> row.sku().equals(skuId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
