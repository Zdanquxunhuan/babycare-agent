package com.yu.babycare;

import com.yu.babycare.agent.*;
import com.yu.babycare.agent.LanguageModel.*;
import com.yu.babycare.knowledge.Knowledge.Evidence;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.test.context.bean.override.convention.TestBean;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"babycare.demo-password=guide-test","babycare.ops-password=ops-test"})
class GuideAcceptanceTests {
    @TestBean Clock storeClock;
    static Clock storeClock() {
        return Clock.fixed(Instant.parse("2026-11-24T01:00:00Z"),ZoneId.of("Asia/Shanghai"));
    }
    @TempDir static Path dir;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("babycare.promotion-fault-file", () -> dir.resolve("promotions-offline").toString());
        r.add("babycare.stock-source", () -> dir.resolve("stock.json").toString());
        r.add("babycare.stock-delay-file", () -> dir.resolve("stock-slow").toString());
        r.add("babycare.stock-timeout", () -> "500ms");
        r.add("babycare.stock-simulated-delay", () -> "20s");
        r.add("babycare.stock-fault-file", () -> dir.resolve("offline").toString());
    }
    @org.springframework.beans.factory.annotation.Value("${babycare.fixtures:fixtures}") Path fixtures;
    @BeforeEach void copyInventory() throws Exception {
        Files.copy(fixtures.resolve("catalog-demo.json"),dir.resolve("stock.json"), StandardCopyOption.REPLACE_EXISTING);
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
    void authenticate(String user,String password) throws Exception {
        token = ok(request("GET","/api/session",null,null)).path("csrf").asString();
        assertEquals(204,request("POST","/api/session/login","application/x-www-form-urlencoded",
                "username="+user+"&password="+password).statusCode());
        token = ok(request("GET","/api/session",null,null)).path("csrf").asString();
    }
    void login(String user) throws Exception {
        authenticate(user,"guide-test");
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
        assertEquals("access_denied",ask("另一家分店多少钱").path("type").asString());
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
            single,
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
        runBrowser("guide-acceptance.cjs");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_BROWSER_TEST", matches="1")
    void policyBrowserLifecycle() throws Exception {
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(plan(Intent.KNOWLEDGE,null,null,null,null));
        when(model.selectEvidence(anyString(),anyList())).thenAnswer(call -> {
            List<Evidence> candidates = call.getArgument(1);
            return candidates.getFirst().id();
        });
        runBrowser("policy-acceptance.cjs");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_COMPARE", matches="1")
    void invoiceWithoutEvidenceInHybridBrowser() throws Exception {
        // Replay the two qwen-plus response bodies at the HTTP boundary, including the missing id.
        var adapter = new BailianModel(json, URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
            "qwen-plus","test-only");
        var transport = mock(HttpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter,"http",transport);
        var intent = completion("{\"intent\":\"KNOWLEDGE\"}");
        var selection = completion("{}");
        when(transport.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(intent, selection);

        String invoice = "发票抬头怎么改？";
        when(model.interpret(eq(invoice),eq("STORE-01"),any(),anySet())).thenAnswer(call ->
            adapter.interpret(invoice,"STORE-01",call.getArgument(2),call.getArgument(3)));
        when(model.selectEvidence(eq(invoice),anyList())).thenAnswer(call -> {
            List<Evidence> candidates = call.getArgument(1);
            assertEquals(Set.of("模拟售后政策 / 第4条 已拆包","模拟售后政策 / 第5条 未拆封"),
                candidates.stream().map(Evidence::section).collect(java.util.stream.Collectors.toSet()));
            return adapter.selectEvidence(invoice,candidates);
        });

        runBrowser("policy-acceptance.cjs","--invoice-only");
        verify(model).selectEvidence(eq(invoice),anyList());
        verify(transport,times(2)).send(any(HttpRequest.class),any());
    }

    private HttpResponse<String> completion(String content) {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(json.writeValueAsString(
            Map.of("choices",List.of(Map.of("message",Map.of("content",content))))));
        return response;
    }

    private void runBrowser(String script,String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("node","src/test/browser/"+script));
        command.addAll(List.of(arguments));
        var runner = new ProcessBuilder(command).inheritIO();
        runner.environment().put("BABYCARE_URL","http://127.0.0.1:"+port);
        runner.environment().put("BABYCARE_DEMO_PASSWORD","guide-test");
        runner.environment().put("BABYCARE_OPS_PASSWORD","ops-test");
        runner.environment().put("BABYCARE_PROMOTION_FAULT_FILE",dir.resolve("promotions-offline").toString());
        runner.environment().put("BABYCARE_STOCK_FAULT_FILE",dir.resolve("offline").toString());
        runner.environment().put("BABYCARE_STOCK_DELAY_FILE",dir.resolve("stock-slow").toString());
        runner.environment().put("BABYCARE_STOCK_SOURCE",dir.resolve("stock.json").toString());
        var process = runner.start();
        try {
            assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS),"浏览器用例超时");
            assertEquals(0,process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_BROWSER_TEST",matches="1")
    void promotionFollowUpInBrowser() throws Exception {
        var initial = purchase(2,"220",Scope.TOTAL,null);
        var activity = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null,null,null,null,true);
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(initial,activity,activity);
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE,Check.PROMOTION,Check.STOCK);
        runBrowser("promotion-acceptance.cjs");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_BROWSER_TEST",matches="1")
    void stockTimeoutAlertAndRecoveryInBrowser() throws Exception {
        var initial = new Plan(Intent.PRODUCT,null,"纸尿裤","L",null,true,Set.of("绒朵"),null,null,null,null,
            2,new BigDecimal("218"),Scope.TOTAL,true);
        var followUp = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null);
        when(model.interpret(anyString(),eq("STORE-01"),any(),anySet())).thenReturn(initial,followUp);
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        runBrowser("stock-timeout.cjs");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_BROWSER_TEST",matches="1")
    void storeManualStockInBrowser() throws Exception {
        var shopping = purchase(2,"300",Scope.TOTAL,Set.of("绒朵"));
        var followUp = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null);
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(shopping);
        when(model.interpret(eq("手上有明显污垢该怎么清洁？"),anyString(),any(),anySet()))
            .thenReturn(plan(Intent.KNOWLEDGE,null,null,null,null));
        when(model.interpret(eq("再查一下"),anyString(),any(),anySet())).thenReturn(followUp);
        when(model.selectEvidence(anyString(),anyList())).thenReturn("hand-hygiene-1.0");
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.STOCK);
        runBrowser("stock-mode.cjs");
        // Only the unaffected store and the resumed request reached the purchase planner.
        verify(model,times(2)).firstCheck(any(),anyInt(),anyLong());
    }

    @Test void stockModeRejectsUnauthorizedAndStaleWrites() throws Exception {
        String path = "/api/ops/stock-mode/STORE-01";
        login("direct");
        assertEquals(403,request("POST",path,"application/json",
            "{\"expectedRevision\":0,\"paused\":true,\"reason\":\"测试\"}").statusCode());
        assertEquals(403,request("GET",path+"/history",null,null).statusCode());
        authenticate("ops","ops-test");
        var history = ok(request("GET",path+"/history",null,null));
        long rev = history.isEmpty() ? 0 : history.path(0).path("revision").asLong();
        String body = json.writeValueAsString(Map.of("expectedRevision",rev,"paused",true,"reason","测试暂停"));
        assertEquals(404,request("POST","/api/ops/stock-mode/UNKNOWN","application/json",body).statusCode());
        assertEquals(400,request("POST",path,"application/json",body.replace("测试暂停"," ")).statusCode());
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> submit = () -> request("POST",path,"application/json",body).statusCode();
            var one = pool.submit(submit);
            var two = pool.submit(submit);
            assertEquals(Set.of(200,409),Set.of(one.get(),two.get()));
        } finally {
            var latest = ok(request("GET",path+"/history",null,null)).path(0);
            if (latest.path("paused").asBoolean()) {
                String restore = json.writeValueAsString(Map.of("expectedRevision",latest.path("revision").asLong(),
                    "paused",false,"reason","测试恢复"));
                ok(request("POST",path,"application/json",restore));
            }
        }
        var events = ok(request("GET",path+"/history",null,null));
        assertEquals(rev+2,events.path(0).path("revision").asLong());
        assertEquals("ops",events.path(1).path("operator").asString());
    }

    @Test void directOfferDistinguishesTimeoutFromOffline() throws Exception {
        login("direct");
        String path = "/api/stores/STORE-01/offers/DEMO-008";
        Path slow = Files.createFile(dir.resolve("stock-slow"));
        try {
            assertEquals(504, request("GET",path,null,null).statusCode());
            assertEquals(403, request("GET","/api/stores/STORE-02/offers/DEMO-008",null,null).statusCode());
        } finally { Files.delete(slow); }
        Path offline = Files.createFile(dir.resolve("offline"));
        try { assertEquals(503, request("GET",path,null,null).statusCode()); }
        finally { Files.delete(offline); }
        assertEquals(3,ok(request("GET",path,null,null)).path("packs").asInt());
    }

    private Plan listTwoBrands() {
        return plan(Intent.PRODUCT,"L",null,Set.of("芽禾","绒朵"),null);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_BROWSER_TEST",matches="1")
    void displayedProductsInBrowser() throws Exception {
        var second = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null,2,null,null,true,
            new Reference(ReferenceKind.POSITION,2));
        var first = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null);
        when(model.interpret(anyString(),eq("STORE-01"),any(),anySet())).thenReturn(listTwoBrands(),second,first);
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        runBrowser("displayed-products.cjs");
    }

    private void stubQuoteAndPolicy() {
        var compound = new Plan(Intent.PRODUCT_POLICY,null,null,null,null,null,null,null,null,null,null,
            2,null,null,true,new Reference(ReferenceKind.POSITION,2),"如果拆了一包还能退吗？");
        when(model.interpret(anyString(),eq("STORE-01"),any(),anySet())).thenReturn(listTwoBrands(),compound);
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PROMOTION);
        when(model.selectEvidence(anyString(),anyList())).thenAnswer(call -> {
            List<Evidence> candidates = call.getArgument(1);
            return candidates.stream().filter(e -> e.section().contains("已拆包")).findFirst().orElseThrow().id();
        });
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_BROWSER_TEST",matches="1")
    void quoteAndPolicyInBrowser() throws Exception {
        stubQuoteAndPolicy();
        runBrowser("policy-acceptance.cjs","--quote-policy");
    }

    @Test void quoteAndPolicyAreVerifiedIndependently() throws Exception {
        String ops = "/api/ops/policies";
        String key = "quote-returns-"+UUID.randomUUID();
        String revision = ops+"/"+key+"/1";
        authenticate("ops","ops-test");
        var draft = Map.of("key",key,"title","模拟售后政策","scope","STORE-01",
            "markdown",Files.readString(Path.of("fixtures/policies/returns-v2.md")));
        ok(request("POST",ops,"application/json",json.writeValueAsString(draft)));
        assertEquals(204,request("POST",revision+"/publish",null,null).statusCode());
        login("direct");
        String clause = "如果拆了一包还能退吗？";
        String question = "第二款买两包，今天有活动吗？"+clause;
        stubQuoteAndPolicy();
        try {
            assertEquals(2,ask("本店有哪些 L 码纸尿裤？只看芽禾和绒朵。").path("products").size());
            var reply = ask(question);
            assertEquals(1,reply.path("products").size());
            assertEquals("DEMO-008",reply.path("products").path(0).path("sku").asString());
            assertEquals(3,reply.path("products").path(0).path("packs").asInt());
            assertEquals(218,reply.path("quotes").path("DEMO-008").path("payable").asInt());
            assertEquals(1,reply.path("citations").size());
            String link = reply.path("citations").path(0).path("source").asString();
            assertTrue(reply.path("policyText").asString().contains("照片"));
            assertEquals(reply.path("policyText").asString(),request("GET",link,null,null).body());

            Path offline = Files.createFile(dir.resolve("offline"));
            try {
                var partial = ask(question);
                assertEquals("stock_unavailable",partial.path("type").asString());
                assertEquals(0,partial.path("products").size());
                assertEquals(reply.path("citations"),partial.path("citations"));
            } finally { Files.delete(offline); }

            doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(model).selectEvidence(anyString(),anyList());
            var degraded = ask(question);
            assertEquals(reply.path("quotes"),degraded.path("quotes"));
            assertEquals(0,degraded.path("citations").size());
            assertTrue(degraded.path("policyText").asString().contains("人工确认"));
        } finally {
            authenticate("ops","ops-test");
            assertEquals(204,request("POST",revision+"/withdraw",null,null).statusCode());
        }
        authenticate("direct","guide-test");
        var history = ok(request("GET","/api/conversations/"+chat,null,null)).path("turns").path(1).path("reply");
        assertFalse(history.toString().contains("照片"));
        assertTrue(history.path("policyText").asString().contains("人工确认"));
        assertEquals(0,history.path("citations").size());
        assertEquals(218,history.path("totals").path("DEMO-008").asInt());
        var fresh = ask(question);
        assertEquals(218,fresh.path("totals").path("DEMO-008").asInt());
        assertEquals(0,fresh.path("citations").size());
        assertTrue(fresh.path("policyText").asString().contains("人工确认"));
    }

    @Test void referencesClarifyWithoutLookingUpAnotherProduct() throws Exception {
        login("direct");
        var ordinary = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null);
        var ambiguous = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null,2,null,null,true,
            new Reference(ReferenceKind.POSITION,1)); // Model guessed an ordinal for “这款”.
        var byMeaning = new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null,null,null,null,null,
            new Reference(ReferenceKind.POSITION,2));
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(
            ordinary,listTwoBrands(),ambiguous,ordinary,byMeaning,ordinary,
            plan(Intent.PRODUCT,"L",null,Set.of("芽禾"),null),ordinary);
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        assertEquals("clarify",ask("第二款呢？").path("type").asString());
        assertEquals(2,ask("本店 L 码纸尿裤，只看芽禾和绒朵").path("products").size());
        assertEquals("clarify",ask("这款买两包有活动吗？").path("type").asString());
        assertEquals("clarify",ask("第三款").path("type").asString());
        verify(model,never()).firstCheck(any(),anyInt(),anyLong());
        // The ordinal clarification retained the purchase and promotion conditions.
        assertEquals(218,ask("靠后那个").path("totals").path("DEMO-008").asInt());
        assertEquals(218,ask("这款再查一下").path("totals").path("DEMO-008").asInt());
        assertEquals("DEMO-003",ask("换一批，只看芽禾").path("products").path(0).path("sku").asString());
        assertEquals("clarify",ask("第二款").path("type").asString());
    }

    @Test void focusedSkuIsRecheckedWhenStockChanges() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(listTwoBrands(),
            new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null,2,null,null,true));
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        assertEquals(3,ask("只看芽禾和绒朵的 L 码纸尿裤").path("products").path(1).path("packs").asInt());
        Path stock = dir.resolve("stock.json");
        var snapshot = json.readTree(Files.readString(stock));
        for (var item : snapshot.path("products")) if ("DEMO-008".equals(item.path("sku").asString())) {
            for (var row : item.path("store_offers")) if ("STORE-01".equals(row.path("store_id").asString()))
                ((tools.jackson.databind.node.ObjectNode)row).put("available_packs",1);
        }
        Files.writeString(stock,json.writeValueAsString(snapshot));
        var latest = ask("第二款两包还能买吗？");
        assertEquals("empty",latest.path("type").asString());
        assertTrue(latest.path("text").asString().contains("仅有 1 包库存"));
        assertTrue(latest.path("products").isEmpty());
    }

    @Test void promotionBudgetAndStoreBoundaries() throws Exception {
        var promo = new Plan(Intent.PRODUCT,null,"纸尿裤","L",null,null,Set.of("绒朵"),null,null,null,null,
            2,new BigDecimal("218"),Scope.TOTAL,true);
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(promo);
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        login("direct");
        var direct = ask("绒朵两包总价218元以内，有活动吗？");
        assertEquals(218,direct.path("totals").path("DEMO-008").asInt());
        login("franchise");
        assertTrue(ask("绒朵两包总价218元以内，有活动吗？").path("products").isEmpty());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void crossStoreFollowUpStopsBeforePlanning(CapturedOutput logs) throws Exception {
        login("direct");
        var promo = new Plan(Intent.PRODUCT,null,"纸尿裤","L",null,true,Set.of("绒朵"),null,null,null,null,
            2,new BigDecimal("218"),Scope.TOTAL,true);
        when(model.interpret(anyString(),eq("STORE-01"),any(),anySet())).thenReturn(promo);
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        var local = ask("本店绒朵 L 码两包有库存吗？算上活动共 218 元够吗？");
        assertEquals(218,local.path("totals").path("DEMO-008").asInt());
        assertEquals(3,local.path("products").path(0).path("packs").asInt());
        clearInvocations(model);
        var denied = ask("那 B 店呢？");
        assertEquals("access_denied",denied.path("type").asString());
        for (String key : List.of("products","citations","quotes","totals","trace"))
            assertTrue(denied.path(key).isEmpty(),key);
        verifyNoInteractions(model);
        var view = ok(request("GET","/api/conversations/"+chat,null,null));
        String requestId = view.path("turns").path(1).path("messageId").asString();
        assertTrue(logs.getOut().contains("turn="+requestId+" chat="+chat+" guide=direct store=STORE-01 result=access_denied"));
    }

    @Test void deniedTurnPreservesPendingQuestion() throws Exception {
        login("franchise");
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenReturn(purchase(2,"220",null,null));
        assertEquals("clarify",ask("两包预算220元").path("type").asString());
        String sql = "SELECT context_json FROM bc_chat_checkpoint WHERE chat_id=?";
        String pending = db.queryForObject(sql,String.class,chat);
        assertEquals("access_denied",ask("那ＳＴＯＲＥ－０１呢？").path("type").asString());
        assertEquals(pending,db.queryForObject(sql,String.class,chat));
        verify(model,times(1)).interpret(anyString(),anyString(),any(),anySet());
    }

    @Test void resumeCheckpointFromBeforePromotions() throws Exception {
        login("direct");
        UUID oldMessage = UUID.randomUUID();
        String context = """
            {"conditions":{"size":"L","quantity":2,"category":"纸尿裤","budget":null,"totalBudget":220,
            "pendingBudget":null,"inStock":false,"brands":[],"excluded":[]},"pendingQuestion":null}
            """;
        String oldReply = """
            {"type":"products","text":"历史报价","products":[],"citations":[],"quantity":2,"totals":{},"trace":[]}
            """;
        db.update("UPDATE bc_chat_checkpoint SET version=1,context_json=? WHERE chat_id=?",context,chat);
        db.update("INSERT INTO bc_chat_turn (chat_id,version,message_id,question,reply_json) VALUES (?,1,?,?,?)",
            chat,oldMessage.toString(),"之前的查询",oldReply);
        var resumed = ok(request("POST","/api/conversations/current",null,null));
        assertEquals(chat,resumed.path("id").asString());
        assertEquals("历史报价",resumed.path("turns").path(0).path("reply").path("text").asString());
        version=1;
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenAnswer(call -> {
            Context restored=call.getArgument(2);
            assertNull(restored.conditions().promotions());
            return new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null,null,null,null,true);
        });
        when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.STOCK);
        assertEquals(218,ask("那本店今天有活动吗？").path("totals").path("DEMO-008").asInt());
    }

    @Autowired JdbcTemplate db;
    @Autowired InMemoryUserDetailsManager accounts;

    @Test void checkpointRetriesAndConcurrentVersion() throws Exception {
        login("direct");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(model.interpret(anyString(),anyString(),any(),anySet())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(15,TimeUnit.SECONDS));
            return purchase(2,"220",null,null);
        });
        String path = "/api/conversations/"+chat+"/messages";
        var input = Map.of("messageId",UUID.randomUUID(),"version",0,"text","买两包 L 码，预算220元");
        String payload = json.writeValueAsString(input);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> request("POST",path,"application/json",payload));
            try {
                assertTrue(entered.await(15,TimeUnit.SECONDS));
                assertEquals(409,request("POST",path,"application/json",payload).statusCode());
            } finally {
                release.countDown();
            }
            assertEquals(1,ok(first.get(15,TimeUnit.SECONDS)).path("version").asInt());
        }
        // A new login with a fresh cookie jar can safely replay the committed message.
        init();
        authenticate("direct","guide-test");
        assertEquals(1,ok(request("POST",path,"application/json",payload)).path("turns").size());
        var conflicting = new HashMap<String,Object>(input);
        conflicting.put("text","另一条消息");
        assertEquals(409,request("POST",path,"application/json",json.writeValueAsString(conflicting)).statusCode());
        conflicting.put("messageId",UUID.randomUUID());
        assertEquals(409,request("POST",path,"application/json",json.writeValueAsString(conflicting)).statusCode());
        verify(model,times(1)).interpret(anyString(),anyString(),any(),anySet());
    }

    @Test void checkpointExpiryAndRevokedAccess() throws Exception {
        login("direct");
        String path = "/api/conversations/"+chat;
        var original = accounts.loadUserByUsername("direct");
        try {
            accounts.updateUser(User.withUserDetails(original).roles("OPS").build());
            assertEquals(403,request("GET",path,null,null).statusCode());
            assertEquals(403,request("GET","/api/stores/STORE-01/offers/DEMO-008",null,null).statusCode());
            assertEquals(403,request("GET","/api/knowledge/citations/sample",null,null).statusCode());
            assertEquals(403,request("GET","/api/ops/policies",null,null).statusCode());
            assertEquals(403,request("POST","/api/conversations/current",null,null).statusCode());
        } finally {
            accounts.updateUser(original);
        }
        db.update("UPDATE bc_chat_checkpoint SET expires_at=0 WHERE chat_id=?",chat);
        assertEquals(404,request("GET",path,null,null).statusCode());
        var replacement=ok(request("POST","/api/conversations/current",null,null));
        assertNotEquals(chat,replacement.path("id").asString());
        assertEquals(0,replacement.path("version").asInt());
    }

    @Test void failureReleasesClaimAndExpiredLeaseCanBeRetried() throws Exception {
        login("direct");
        db.update("UPDATE bc_chat_checkpoint SET claim_token='abandoned',claim_until=1 WHERE chat_id=?",chat);
        when(model.interpret(anyString(),anyString(),any(),anySet()))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
            .thenReturn(purchase(2,"220",null,null));
        String path="/api/conversations/"+chat+"/messages";
        String input=json.writeValueAsString(Map.of("messageId",UUID.randomUUID(),"version",0,"text","两包预算220元"));
        assertEquals(503,request("POST",path,"application/json",input).statusCode());
        assertEquals(1,ok(request("POST",path,"application/json",input)).path("version").asInt());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BABYCARE_RESTART_PHASE",matches="prepare|resume")
    void checkpointBrowserAcrossProcesses() throws Exception {
        String phase = System.getenv("BABYCARE_RESTART_PHASE");
        if (phase.equals("prepare")) {
            when(model.interpret(eq("买两包 L 码，预算 220 元"),eq("STORE-01"),any(),anySet()))
                .thenReturn(purchase(2,"220",null,null));
        } else {
            when(model.interpret(eq("总价"),eq("STORE-01"),any(),anySet())).thenAnswer(call -> {
                Context context = call.getArgument(2);
                assertTrue(context.pendingQuestion().contains("每包上限"));
                assertEquals("L",context.conditions().size());
                assertEquals(2,context.conditions().quantity());
                assertEquals(220,context.conditions().pendingBudget().intValueExact());
                return new Plan(Intent.PRODUCT,null,null,null,null,null,null,null,null,null,null,null,null,Scope.TOTAL);
            });
            when(model.firstCheck(any(),anyInt(),anyLong())).thenReturn(Check.PRICE);
        }
        runBrowser("checkpoint-acceptance.cjs",phase);
    }

    @Test void recommendAndRefine() throws Exception {
        login("direct");
        when(model.interpret(anyString(),anyString(),any(Context.class),anySet())).thenReturn(
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
    @Autowired com.yu.babycare.knowledge.Knowledge search;

    @Test void policyLifecycleAndBaseline() throws Exception {
        String key = "returns-"+UUID.randomUUID();
        String ops = "/api/ops/policies";
        String family = ops+"/"+key;
        String question = "顾客拆包了还能退吗？";
        when(model.interpret(anyString(),anyString(),any(),anySet()))
            .thenReturn(plan(Intent.KNOWLEDGE,null,null,null,null));
        when(model.selectEvidence(anyString(),anyList())).thenAnswer(call -> {
            List<Evidence> candidates = call.getArgument(1);
            return candidates.getFirst().id();
        });

        authenticate("ops","ops-test");
        String v1 = Files.readString(Path.of("fixtures/policies/returns-v1.md"));
        var draft = new HashMap<String,Object>(Map.of("key",key,"title","模拟售后政策","scope","STORE-01","markdown",v1));
        assertEquals(1,ok(request("POST",ops,"application/json",json.writeValueAsString(draft))).path("version").asInt());
        assertEquals(v1,request("GET",family+"/1",null,null).body());
        login("direct");
        assertEquals("no_evidence",ask(question).path("type").asString());
        verify(model,never()).selectEvidence(anyString(),anyList());
        assertEquals(403,request("POST",family+"/1/publish",null,null).statusCode());

        authenticate("ops","ops-test");
        assertEquals(204,request("POST",family+"/1/publish",null,null).statusCode());
        login("direct");
        var first = ask(question);
        String link = first.path("citations").path(0).path("source").asString();
        assertTrue(request("GET",link,null,null).body().contains("店长"));
        assertTrue(first.path("text").asString().contains("店长"));
        String msg = ok(request("GET","/api/conversations/"+chat,null,null)).path("turns").path(0).path("messageId").asString();
        assertEquals(204,request("POST","/api/conversations/"+chat+"/messages/"+msg+"/feedback","application/json",
            "{\"helpful\":false}").statusCode());

        for (String q : List.of("政策第4条怎么处理","顾客拆包了还能退吗？","包装打开了，客人又不想要了，怎么办？","发票抬头怎么改？")) {
            long start = System.nanoTime();
            var hits = search.search("STORE-01",q);
            long micros = (System.nanoTime()-start)/1000;
            var sections = hits.stream().map(Evidence::section).toList();
            System.out.printf("V1.0a | %s | %s | %d us | %s%n",q,sections,micros,ask(q).path("citations"));
        }
        login("franchise");
        assertEquals(404,request("GET",link,null,null).statusCode());
        assertEquals("no_evidence",ask(question).path("type").asString());

        authenticate("ops","ops-test");
        draft.put("markdown",Files.readString(Path.of("fixtures/policies/returns-v2.md")));
        assertEquals(2,ok(request("POST",ops,"application/json",json.writeValueAsString(draft))).path("version").asInt());
        login("direct");
        assertTrue(ask(question).path("text").asString().contains("店长"));
        authenticate("ops","ops-test");
        assertEquals(204,request("POST",family+"/2/publish",null,null).statusCode());
        assertEquals(409,request("POST",family+"/1/withdraw",null,null).statusCode());
        login("direct");
        var second = ask(question);
        assertTrue(second.path("text").asString().contains("照片"));
        assertEquals("2",second.path("citations").path(0).path("version").asString());
        assertEquals(404,request("GET",link,null,null).statusCode());
        authenticate("ops","ops-test");
        assertEquals(204,request("POST",family+"/2/withdraw",null,null).statusCode());
        draft.put("expiresOn","2000-01-01");
        ok(request("POST",ops,"application/json",json.writeValueAsString(draft)));
        assertEquals(409,request("POST",family+"/3/publish",null,null).statusCode());
        login("direct");
        assertEquals("no_evidence",ask(question).path("type").asString());
        assertEquals(404,request("GET",second.path("citations").path(0).path("source").asString(),null,null).statusCode());
    }
}
