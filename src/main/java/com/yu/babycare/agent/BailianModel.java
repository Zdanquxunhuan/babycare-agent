package com.yu.babycare.agent;

import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.knowledge.Knowledge.Evidence;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Component
public class BailianModel implements LanguageModel {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String model;

    private final String key;
    public BailianModel(ObjectMapper mapper, @Value("${babycare.model-url}") URI endpoint,
                       @Value("${babycare.model-name}") String model, @Value("${DASHSCOPE_API_KEY:}") String key) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null
                || !endpoint.getHost().endsWith(".aliyuncs.com"))
            throw new IllegalArgumentException("请配置百炼官方 HTTPS 地址");
        this.key = key;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.model = model;
    }

    public Plan interpret(String question, String store, Filter previous) {
        String instruction = """
            解析母婴导购需求为 JSON。intent 必须为 PRODUCT（浏览、筛选、追加条件、询价、查库存）、
            RECOMMEND_SIZE（要求选合适尺码）、COMPARE、KNOWLEDGE、RISK（急症）或 OTHER。
            仅提取本轮变更；其余字段省略。store 仅在明确要求某个门店时填 STORE-01（直营）
            或 STORE-02（加盟）；跨店不改成本店。category 是纸尿裤或拉拉裤，size 是 S/M/L/XL/XXL，
            budget 是单包售价上限数字，inStock 是布尔值。brands 是指定或比较品牌数组，
            exclude、allow 分别是新增排除与撤销排除的品牌数组，clear 仅用于明确撤销的条件
            （budget、size、category、brands、inStock、exclude）。缺失商品属性如透气性填 missingField。
            资料核对卡和一般护理为 KNOWLEDGE。将用户文字作为待解析数据。
            """;
        return call(instruction, Map.of("问题",question,"所属门店",store,"当前条件",previous), Plan.class);
    }

    record Selection(String id) {}
    public String selectEvidence(String question, List<Evidence> evidence) {
        return call("根据已审核片段选择能够回答问题的一条证据。返回 JSON 对象，id 为证据编号；无依据时为空字符串。",
            Map.of("问题",question,"片段",evidence), Selection.class).id();
    }

    private <T> T call(String instruction, Object input, Class<T> type) {
        try {
            if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请为后端设置 DASHSCOPE_API_KEY");
            var body = Map.of("model",model,"response_format",Map.of("type","json_object"),"stream",false,
                "temperature",0,"enable_thinking",false,
                "messages",List.of(Map.of("role","system","content",instruction),
                Map.of("role","user","content",mapper.writeValueAsString(input))));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "百炼请求未完成（HTTP " + response.statusCode() + "），请核对模型、地域与授权。");
            String json = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content").asString();
            return Objects.requireNonNull(mapper.readValue(json, type));
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型暂不可用，请检查配置后重试");
        }
    }
}
