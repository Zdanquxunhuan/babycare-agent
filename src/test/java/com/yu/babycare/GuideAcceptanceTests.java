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
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(new Plan(Intent.PRODUCT,"STORE-02",null,null,null,null,null,null,null,null,null));
        assertEquals("access_denied",ask("查加盟店价格").path("type").asString());
        String first=chat;
        assertEquals(204,request("POST","/api/session/logout",null,null).statusCode());
        login("franchise");
        assertEquals(404,request("GET","/api/conversations/"+first,null,null).statusCode());
        var other=ok(request("GET","/api/stores/STORE-02/offers/DEMO-008",null,null));
        assertEquals(124,other.path("price").asInt());
        assertEquals(6,other.path("packs").asInt());
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(plan(Intent.PRODUCT,"L",150,null,null));
        var rows=ask("150 元以内").path("products");
        assertTrue(rows.toString().contains("DEMO-018"));
        assertFalse(rows.toString().contains("DEMO-023"));
    }

    private Plan purchase(Integer quantity, String amount, Scope scope, Set<String> brands) {
        return new Plan(Intent.PRODUCT,null,"纸尿裤","L",
            amount==null ? null : new BigDecimal(amount),null,brands,null,null,null,null,quantity,null,scope);
    }

    @Test void clarifyBudgetThenComputeAndKeepQuantity() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(
            purchase(2,"220",null,null), purchase(null,null,Scope.TOTAL,null),
            purchase(null,"212",null,null), purchase(null,"211.99",null,null));
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        assertEquals("clarify",ask("两包预算220元").path("type").asString());
        verify(model,never()).firstCheck(any(),anyInt(),anyLong());
        var good = ask("是总预算");
        assertEquals(1,good.path("products").size());
        assertEquals(212,good.path("totals").path("DEMO-003").asInt());
        assertEquals(2,good.path("quantity").asInt());
        assertEquals(1,ask("改为212元").path("products").size());
        var none = ask("最多211.99元");
        assertEquals("empty",none.path("type").asString());
        assertFalse(none.path("trace").toString().contains("库存核验 ·"));
    }

    @Test void quantityShortfallAndStoreScope() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(
            purchase(4,"999",Scope.TOTAL,Set.of("绒朵")));
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.STOCK);
        assertTrue(ask("买四包绒朵，总共999元以内").path("text").asString().contains("3 包库存"));
        assertEquals(204,request("POST","/api/session/logout",null,null).statusCode());
        login("franchise");
        var reply = ask("买四包绒朵，总共999元以内");
        assertEquals(496,reply.path("totals").path("DEMO-008").asInt());
        assertEquals(6,reply.path("products").path(0).path("packs").asInt());
    }

    @Test void omittedBrandCannotBroadenPurchase() throws Exception {
        login("direct");
        when(model.interpret(anyString(),eq("STORE-01"),any(),eq(Set.of("绒朵"))))
            .thenReturn(purchase(4,"800",Scope.TOTAL,null));
        var reply = ask("本店绒朵 L 码纸尿裤买四包，总共最多 800 元");
        assertEquals("clarify",reply.path("type").asString());
        assertTrue(reply.path("products").isEmpty());
        verify(model,never()).firstCheck(any(),anyInt(),anyLong());
    }

    @Test void explicitUnitPriceSurvivesMissingModelScope() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(),anySet()))
            .thenReturn(purchase(2,"120",null,null));
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        var reply = ask("本店 L 码纸尿裤想买两包，每包不超过 120 元");
        assertEquals("products",reply.path("type").asString());
        assertEquals(238,reply.path("totals").path("DEMO-008").asInt());
    }

    @Test void ordinaryBrowsingCapKeepsUnitMeaningWhenModelGuessesTotal() throws Exception {
        login("direct");
        var overinterpreted = new Plan(Intent.PRODUCT,null,"纸尿裤",null,null,null,null,null,null,null,null,
            null,new BigDecimal("200"),Scope.TOTAL);
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(overinterpreted,
            plan(Intent.RECOMMEND_SIZE,null,null,null,null));
        assertEquals("products",ask("本店 200 元以内有哪些纸尿裤？").path("type").asString());
        assertTrue(ask("帮我选一款合适尺码的").path("text").asString().contains("什么尺码"));
    }

    @Test void missingQuantityInvalidInputAndStockFailure() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(
            purchase(null,"220",Scope.TOTAL,null),purchase(0,null,null,null),
            purchase(2,null,null,null));
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        assertTrue(ask("总共220元").path("text").asString().contains("几包"));
        assertEquals("clarify",ask("0包").path("type").asString());
        Files.createFile(dir.resolve("offline"));
        try { assertEquals("stock_unavailable",ask("两包").path("type").asString()); }
        finally { Files.delete(dir.resolve("offline")); }
        assertEquals(212,ask("再查一次").path("totals").path("DEMO-003").asInt());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_BROWSER_TEST", matches="1")
    void browserAcceptanceWithModelFixture() throws Exception {
        // Only model extraction and tool choice are fixtures. Browser, auth, conversations and tools are real.
        var single = plan(Intent.PRODUCT,"L",null,Set.of("绒朵"),null);
        var knowledge = plan(Intent.KNOWLEDGE,null,null,null,null);
        when(model.selectEvidence(anyString(),anyList())).thenReturn("hand-hygiene-1.0");
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(
            new Plan(Intent.PRODUCT,"STORE-02",null,null,null,null,null,null,null,null,null),single,
            plan(Intent.PRODUCT,null,200,null,null),plan(Intent.RECOMMEND_SIZE,null,null,null,null),
            plan(Intent.PRODUCT,"L",null,null,null),plan(Intent.PRODUCT,null,120,null,Set.of("芽禾")),
            plan(Intent.PRODUCT,null,100,null,null),plan(Intent.COMPARE,"L",null,Set.of("芽禾","绒朵"),null),
            single,single,knowledge,knowledge,plan(Intent.PRODUCT,"L",null,null,null),
            purchase(2,"220",Scope.TOTAL,null), purchase(null,"212",Scope.TOTAL,null),
            purchase(null,"200",Scope.TOTAL,null), purchase(4,"800",Scope.TOTAL,Set.of("绒朵")),
            purchase(2,"220",null,null), purchase(null,null,Scope.TOTAL,null),
            purchase(2,"120",Scope.UNIT,null), purchase(null,"220",Scope.TOTAL,null),
            purchase(2,null,null,null), purchase(null,null,null,null), purchase(null,null,null,null),
            single,plan(Intent.PRODUCT,"L",150,null,null));
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        var runner = new ProcessBuilder("node","src/test/browser/guide-acceptance.cjs")
            .inheritIO();
        runner.environment().put("BABYCARE_URL","http://127.0.0.1:"+port);
        runner.environment().put("BABYCARE_DEMO_PASSWORD","guide-test");
        runner.environment().put("BABYCARE_STOCK_FAULT_FILE",dir.resolve("offline").toString());
        var process = runner.start();
        try {
            assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS),"浏览器用例超时");
            assertEquals(0,process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    @Test void recommendAndRefine() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(Filter.class),anySet())).thenReturn(
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
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(new Plan(Intent.COMPARE,null,"纸尿裤","L",null,null,
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
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(plan(Intent.KNOWLEDGE,null,null,null,null));
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
