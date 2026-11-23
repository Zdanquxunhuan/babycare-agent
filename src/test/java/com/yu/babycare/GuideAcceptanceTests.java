package com.yu.babycare;

import com.yu.babycare.agent.*;
import com.yu.babycare.agent.LanguageModel.*;
import com.yu.babycare.catalog.Catalog.Filter;
import com.yu.babycare.knowledge.Knowledge.Evidence;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="babycare.demo-password=guide-test")
class GuideAcceptanceTests {
    @TempDir static Path dir;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("babycare.stock-fault-file", () -> dir.resolve("offline").toString());
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean LanguageModel model;
    HttpClient browser;
    String token, chat;
    int version;
    @BeforeEach void init() {
        browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> request(String method,String path,String type,String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null) b.header("X-CSRF-TOKEN",token);
        if(type!=null) b.header("Content-Type",type);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return browser.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(HttpResponse<String> res) {
        assertEquals(200,res.statusCode(),res.body());
        return json.readTree(res.body());
    }
    void login(String user) throws Exception {
        token = ok(request("GET","/api/session",null,null)).path("csrf").asString();
        assertEquals(204,request("POST","/api/session/login","application/x-www-form-urlencoded",
                "username="+user+"&password=guide-test").statusCode());
        token = ok(request("GET","/api/session",null,null)).path("csrf").asString();
        chat = ok(request("POST","/api/conversations",null,null)).path("id").asString();
        version = 0;
    }
    JsonNode ask(String question) throws Exception {
        var input = Map.of("messageId",UUID.randomUUID(),"version",version,"text",question);
        var view = ok(request("POST","/api/conversations/"+chat+"/messages","application/json",json.writeValueAsString(input)));
        version = view.path("version").asInt();
        return view.path("turns").path(version-1).path("reply");
    }
    Plan plan(Intent intent,String size,Integer budget,Set<String> brands,Set<String> excluded) {
        return new Plan(intent,null,"纸尿裤",size,budget==null?null:BigDecimal.valueOf(budget),
            true,brands,excluded,null,null,null);
    }
    @Test void sessionsAndScopedOffers() throws Exception {
        assertEquals(401, request("GET","/api/stores/STORE-01/offers/DEMO-008",null,null).statusCode());
        login("direct");
        var allowed=ok(request("GET","/api/stores/STORE-01/offers/DEMO-008",null,null));
        assertEquals(119,allowed.path("price").asInt());
        assertEquals(3,allowed.path("packs").asInt());
        var denied=request("GET","/api/stores/STORE-02/offers/DEMO-008",null,null);
        assertEquals(403,denied.statusCode());
        assertFalse(json.readTree(denied.body()).has("price"));
        when(model.interpret(anyString(),anyString(),any())).thenReturn(new Plan(Intent.PRODUCT,"STORE-02",null,null,null,null,null,null,null,null,null));
        assertEquals("access_denied",ask("查加盟店价格").path("type").asString());
        String first=chat;
        assertEquals(204,request("POST","/api/session/logout",null,null).statusCode());
        login("franchise");
        assertEquals(404,request("GET","/api/conversations/"+first,null,null).statusCode());
        var other=ok(request("GET","/api/stores/STORE-02/offers/DEMO-008",null,null));
        assertEquals(124,other.path("price").asInt());
        assertEquals(6,other.path("packs").asInt());
        when(model.interpret(anyString(),anyString(),any())).thenReturn(plan(Intent.PRODUCT,"L",150,null,null));
        var rows=ask("150 元以内").path("products");
        assertTrue(rows.toString().contains("DEMO-018"));
        assertFalse(rows.toString().contains("DEMO-023"));
    }
    @Test void recommendAndRefine() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(Filter.class))).thenReturn(
            plan(Intent.PRODUCT,null,200,null,null), plan(Intent.RECOMMEND_SIZE,null,null,null,null),
            plan(Intent.PRODUCT,"L",null,null,null), plan(Intent.PRODUCT,null,120,null,Set.of("芽禾")),
            plan(Intent.PRODUCT,null,100,null,null));
        assertEquals("products",ask("本店 200 元以内有哪些纸尿裤").path("type").asString());
        assertEquals("clarify",ask("帮我推荐尺码").path("type").asString());
        assertEquals(4,ask("L 码，只看有货的").path("products").size());
        var rows=ask("预算改成 120 元，排除芽禾").path("products");
        assertEquals(1,rows.size());
        assertEquals("DEMO-008",rows.path(0).path("sku").asString());
        assertEquals("empty",ask("最多 100 元").path("type").asString());
    }
    @Test void missingFieldAndInventoryFailure() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any())).thenReturn(new Plan(Intent.COMPARE,null,"纸尿裤","L",null,null,
            Set.of("芽禾","绒朵"),null,null,null,null),plan(Intent.PRODUCT,"L",null,Set.of("绒朵"),null));
        var comparison=ask("比较芽禾和绒朵，哪款更透气");
        assertEquals(2,comparison.path("products").size());
        assertTrue(comparison.path("text").asString().contains("缺少"));
        Files.createFile(dir.resolve("offline"));
        try { assertEquals("stock_unavailable",ask("本店绒朵有货吗").path("type").asString()); }
        finally { Files.delete(dir.resolve("offline")); }
        assertEquals(3,ask("再次查询").path("products").path(0).path("packs").asInt());
    }
    @Test void evidenceAndSafety() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any())).thenReturn(plan(Intent.KNOWLEDGE,null,null,null,null));
        when(model.selectEvidence(anyString(),anyList())).thenAnswer(call -> {
            List<Evidence> submitted = call.getArgument(1);
            assertEquals(List.of("hand-hygiene-1.0"),submitted.stream().map(Evidence::id).toList());
            assertFalse(submitted.toString().contains("DEMO-K72"));
            return submitted.getFirst().id();
        });
        assertTrue(ask("手上有明显污垢").path("text").asString().contains("肥皂"));
        assertEquals("no_evidence",ask("资料核对卡上的核对码？").path("type").asString());
        verify(model,times(1)).selectEvidence(anyString(),anyList());
        clearInvocations(model);
        var reply=ask("宝宝现在呼吸困难，还该买哪款");
        assertEquals("safety",reply.path("type").asString());
        assertTrue(reply.path("products").isEmpty());
        verifyNoInteractions(model);
    }
}
