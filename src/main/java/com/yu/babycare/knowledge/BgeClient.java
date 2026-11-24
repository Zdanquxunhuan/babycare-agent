package com.yu.babycare.knowledge;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("hybrid")
class BgeClient {
    private final ObjectMapper json;
    private final URI embed, rerank;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    BgeClient(ObjectMapper json, @Value("${babycare.retrieval.embed-url}") URI embed,
              @Value("${babycare.retrieval.rerank-url}") URI rerank) {
        this.json = json;
        this.embed = embed.resolve("/embed");
        this.rerank = rerank.resolve("/rerank");
    }

    double[][] embed(List<String> texts) {
        var vectors = post(embed, Map.of("inputs",texts,"normalize",true,"truncate",true), double[][].class);
        if (vectors.length != texts.size()) throw invalid();
        for (var v : vectors) {
            if (v == null || v.length != 1024 || Arrays.stream(v).anyMatch(x -> !Double.isFinite(x))
                || Arrays.stream(v).noneMatch(x -> x != 0)) throw invalid();
        }
        return vectors;
    }

    record Score(Integer index, Double score) {}
    List<Knowledge.Evidence> rerank(String query, List<Knowledge.Evidence> hits) {
        if (hits.isEmpty()) return hits;
        var texts = hits.stream().map(e -> passage(e.title(),e.section(),e.excerpt())).toList();
        var scores = post(rerank, Map.of("query",query,"texts",texts,"truncate",true), Score[].class);
        if (scores.length != hits.size()) throw invalid();
        Set<Integer> seen = new HashSet<>();
        for (var row : scores) {
            if (row==null || row.index()==null || row.index()<0 || row.index()>=hits.size()
                || !seen.add(row.index()) || row.score()==null || !Double.isFinite(row.score())) throw invalid();
        }
        return Arrays.stream(scores).sorted(Comparator.comparingDouble(Score::score).reversed()
            .thenComparingInt(Score::index)).map(row -> hits.get(row.index())).toList();
    }

    static String passage(String title, String section, String body) {
        return title + "\n" + section + "\n" + body;
    }

    private <T> T post(URI uri, Object payload, Class<T> type) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        try {
            var response = client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw invalid();
            T result = json.readValue(response.body(),type);
            if (result==null) throw invalid();
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw invalid();
        } catch (IOException | tools.jackson.core.JacksonException ex) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"本地 BGE 服务不可用或返回无效结果");
    }
}
