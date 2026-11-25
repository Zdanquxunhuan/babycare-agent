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
    record Price(String store_id, BigDecimal price_cny, Integer available_packs) {}
    record Product(String sku, String name, String brand, String category, String size, int pieces_per_pack,
                   List<Price> store_offers) {}
    record Fixture(boolean simulated, String description, String snapshot_date, List<StoreSecurity.Store> stores,
                   List<Product> products) {}
    public record Offer(String sku, String name, String brand, String category, String size, int pieces,
                        BigDecimal price, int packs, String snapshot, Instant queriedAt) {}
    public record Filter(String category, String size, BigDecimal budget, boolean inStock,
                         Set<String> brands, Set<String> excluded, Integer quantity, BigDecimal totalBudget,
                         BigDecimal pendingBudget, Boolean promotions) {
        public static Filter empty() { return new Filter(null, null, null, false, Set.of(), Set.of(),null,null,null,null); }
        boolean matchesProduct(Offer row) {
            return (category == null || category.equals(row.category()))
                && (size == null || size.equalsIgnoreCase(row.size()))
                && (brands.isEmpty() || brands.contains(row.brand()))
                && !excluded.contains(row.brand());
        }
    }

    private final Fixture fixture;
    private final StockLookup stock;
    public Catalog(ObjectMapper mapper, @Value("${babycare.fixtures:fixtures}") Path dir,
                   StockLookup stock) {
        fixture = mapper.readValue(dir.resolve("catalog-demo.json").toFile(), Fixture.class);
        this.stock = stock;
        var ids = new HashSet<String>();
        for (Product p : fixture.products()) {
            if (!ids.add(p.sku()) || p.store_offers().stream().anyMatch(o -> o.price_cny().signum()<0
                    || o.available_packs()==null || o.available_packs()<0 || fixture.stores().stream().noneMatch(s -> s.id().equals(o.store_id()))))
                throw new IllegalStateException("商品样本字段无效 " + p.sku());
        }
    }

    private List<Offer> scopedRows(StoreSecurity.Store identity, String requestedStore) {
        if (!identity.id().equals(requestedStore)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Instant now = Instant.now();
        return fixture.products().stream().flatMap(p -> p.store_offers().stream()
            .filter(o -> requestedStore.equals(o.store_id()))
            .map(o -> new Offer(p.sku(), p.name(), p.brand(), p.category(), p.size(), p.pieces_per_pack(),
                o.price_cny(), o.available_packs(), fixture.snapshot_date(), now)))
            .toList();
    }

    public List<String> search(StoreSecurity.Store store, Filter filter) {
        return scopedRows(store, store.id()).stream().filter(filter::matchesProduct).map(Offer::sku).toList();
    }

    public Set<String> mentionedBrands(StoreSecurity.Store store, String question) {
        var matches = new LinkedHashSet<String>();
        for (Product product : fixture.products()) {
            if (question.contains(product.brand()) && product.store_offers().stream()
                    .anyMatch(offer -> store.id().equals(offer.store_id()))) {
                matches.add(product.brand());
            }
        }
        return matches;
    }

    public List<Offer> check(StoreSecurity.Store store, Collection<String> candidates, boolean needsStock, long remainingMs) {
        var rows = scopedRows(store, store.id()).stream().filter(row -> candidates.contains(row.sku())).toList();
        return needsStock ? stock.lookup(store.id(), rows, remainingMs) : rows;
    }

    public List<Offer> query(StoreSecurity.Store identity, String requestedStore, Filter filter) {
        var candidates = scopedRows(identity, requestedStore).stream().filter(filter::matchesProduct).toList();
        var rows = stock.lookup(identity.id(), candidates, Long.MAX_VALUE);
        return rows.stream()
            .filter(row -> filter.budget()==null || row.price().compareTo(filter.budget())<=0)
            .filter(row -> !filter.inStock() || row.packs()>0).toList();
    }

    @GetMapping("/api/stores/{storeId}/offers/{skuId}")
    public Offer offer(Authentication auth, @PathVariable String storeId, @PathVariable String skuId) {
        return query(StoreSecurity.store(auth), storeId, Filter.empty()).stream()
            .filter(row -> row.sku().equals(skuId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
