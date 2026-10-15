package com.coursewise.shopping;

import com.coursewise.shopping.repository.IntentRepository;
import com.coursewise.shopping.repository.ProductRepository;
import com.coursewise.shopping.repository.SellerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.zaxxer.hikari.HikariDataSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在线购物系统 Java v4 黑盒回归测试。
 *
 * <p>测试通过 HTTP 请求检查运行中的 Spring Boot 服务。每个动态用例开始前
 * 清空独立临时 SQLite 库和图片目录；不会在项目源目录写入数据库、图片或报告。
 *
 * <p>运行方式：将本文件放入临时项目副本的
 * {@code src/test/java/com/coursewise/shopping/}，再执行 {@code .\mvnw.cmd test}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ShoppingSystemBlackBoxTest {
    private static final long MAX_PRICE_CENTS = 9_007_199_254_740_991L;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String ADMIN_PASSWORD = "admin1234";
    private static final String BOUNDARY = "----java-v4-test-boundary";
    private static final Path TEST_ROOT = createTestRoot();
    private static final Path DB_FILE = TEST_ROOT.resolve("data/shop.db");
    private static final Path UPLOAD_DIR = TEST_ROOT.resolve("custom-uploads");
    private static final Path MISSING_LEGACY_FILE = TEST_ROOT.resolve("no-legacy-data.json");
    private static final String ADMIN_HASH = SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8()
            .encode(ADMIN_PASSWORD);

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_FILE);
        registry.add("shopping.upload-dir", () -> UPLOAD_DIR.toString());
        registry.add("shopping.legacy-data-file", () -> MISSING_LEGACY_FILE.toString());
        registry.add("server.address", () -> "127.0.0.1");
    }

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ProductRepository products;
    @Autowired private IntentRepository intents;
    @Autowired private SellerRepository sellers;
    @Autowired private ServletWebServerApplicationContext applicationContext;
    @Autowired private javax.sql.DataSource dataSource;

    private String baseUrl;
    private Client seller;
    private Client buyer;

    private static Path createTestRoot() {
        try {
            Path root = Files.createTempDirectory("java-shopping-v4-junit-");
            Files.createDirectories(root.resolve("data"));
            return root;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TestFactory
    Stream<DynamicTest> regressionCases() {
        List<RegressionCase> cases = new ArrayList<>();
        defineCases(cases);
        assertEquals(84, cases.size(), "用例清单应与交付的84项用例一致");
        return cases.stream().map(testCase -> DynamicTest.dynamicTest(
                testCase.id + " " + testCase.title,
                () -> {
                    resetForCase();
                    try {
                        testCase.action.execute();
                        System.out.println("PASS " + testCase.id + " " + testCase.title);
                    } catch (Throwable failure) {
                        System.out.println("FAIL " + testCase.id + " " + testCase.title);
                        throw failure;
                    }
                }));
    }

    private void defineCases(List<RegressionCase> cases) {
        add(cases, "AT-001", "空库查询没有在售商品", () -> {
            assertTrue(json(expect(buyer.get("/api/product"), 200)).path("product").isNull());
        });
        add(cases, "AT-002", "无商品时买家提交意向返回409", () ->
                expect(buyer.postJson("/api/intents", object("name", "买家", "phone", "13800000000")), 409));
        add(cases, "AT-003", "买家首页正常打开", () -> checkContentType(buyer.get("/"), 200, "text/html"));
        add(cases, "AT-004", "卖家后台页面正常打开", () -> checkContentType(buyer.get("/admin.html"), 200, "text/html"));
        add(cases, "AT-005", "样式文件正常提供", () -> checkContentType(buyer.get("/style.css"), 200, "text/css"));
        add(cases, "AT-006", "买家脚本正常提供", () -> checkContentType(buyer.get("/app.js"), 200, "javascript"));
        add(cases, "AT-007", "卖家脚本正常提供", () -> checkContentType(buyer.get("/admin.js"), 200, "javascript"));
        add(cases, "AT-008", "匿名查询后台商品返回401", () -> expect(buyer.get("/api/admin/product"), 401));
        add(cases, "AT-009", "匿名查询后台意向返回401", () -> expect(buyer.get("/api/admin/intents"), 401));
        add(cases, "AT-010", "匿名查询历史返回401", () -> expect(buyer.get("/api/admin/history"), 401));
        add(cases, "AT-011", "匿名冻结商品返回401", () -> expect(admin(buyer, "freeze", object()), 401));
        add(cases, "AT-012", "匿名解冻商品返回401", () -> expect(admin(buyer, "unfreeze", object()), 401));
        add(cases, "AT-013", "匿名开始交易返回401", () -> expect(admin(buyer, "trade/start", object()), 401));
        add(cases, "AT-014", "匿名标记成交返回401", () -> expect(admin(buyer, "trade/success", object()), 401));
        add(cases, "AT-015", "匿名标记交易失败返回401", () -> expect(admin(buyer, "trade/fail", object()), 401));
        add(cases, "AT-016", "匿名确认交易递补返回401", () -> expect(admin(buyer, "trade/advance", object()), 401));
        add(cases, "AT-017", "匿名发布商品返回401", () -> expect(publish(buyer, "1", pngBytes(), "test.png", "image/png"), 401));
        add(cases, "AT-018", "错误卖家密码不能登录", () -> expect(login(seller, "wrong-password"), 401));
        add(cases, "AT-019", "正确登录返回安全Cookie", () -> {
            ApiResponse response = expect(login(seller, ADMIN_PASSWORD), 200);
            String cookie = response.firstHeader("Set-Cookie");
            assertTrue(cookie.contains("HttpOnly"), cookie);
            assertTrue(cookie.contains("SameSite=Strict"), cookie);
            assertTrue(cookie.contains("Path=/"), cookie);
        });
        add(cases, "AT-020", "卖家会话与伪造Cookie隔离", () -> {
            expect(login(seller, ADMIN_PASSWORD), 200);
            expect(seller.get("/api/admin/product"), 200);
            expect(buyer.get("/api/admin/product"), 401);
            expect(buyer.request("GET", "/api/admin/product", null, null,
                    Map.of("Cookie", "seller_session=forged")), 401);
        });
        add(cases, "AT-021", "修改密码时旧密码错误返回400", () -> {
            loginSeller();
            expect(seller.postJson("/api/auth/password",
                    object("oldPassword", "wrong", "newPassword", "abcd1234")), 400);
        });
        add(cases, "AT-022", "修改密码拒绝少于8位", () -> invalidPassword("abc1234"));
        add(cases, "AT-023", "修改密码拒绝纯字母", () -> invalidPassword("abcdefgh"));
        add(cases, "AT-024", "修改密码拒绝纯数字", () -> invalidPassword("12345678"));
        add(cases, "AT-025", "修改密码拒绝空值和null", () -> {
            loginSeller();
            expect(seller.postJson("/api/auth/password", object("oldPassword", ADMIN_PASSWORD,
                    "newPassword", "")), 400);
            expect(seller.postJson("/api/auth/password", object("oldPassword", ADMIN_PASSWORD,
                    "newPassword", null)), 400);
        });
        add(cases, "AT-026", "合规修改密码后旧密码失效", () -> {
            loginSeller();
            expect(seller.postJson("/api/auth/password",
                    object("oldPassword", ADMIN_PASSWORD, "newPassword", "abcd1234")), 200);
            expect(login(new Client(baseUrl, true), ADMIN_PASSWORD), 401);
            expect(login(new Client(baseUrl, true), "abcd1234"), 200);
        });
        add(cases, "AT-027", "发布商品后金额和状态正确", () -> {
            loginSeller();
            JsonNode created = json(expect(publish(seller, "12.50", pngBytes(), "test.png", "image/png"), 201));
            JsonNode product = publicProduct();
            assertEquals(created.path("id").asLong(), product.path("id").asLong());
            assertEquals(1250, product.path("priceCents").asLong());
            assertEquals("active", product.path("status").asText());
            assertTrue(product.path("buyable").asBoolean());
        });
        add(cases, "AT-028", "自定义图片目录图片可读取", () -> {
            loginSeller();
            expect(publish(seller, "12.50", pngBytes(), "test.png", "image/png"), 201);
            assertArrayEquals(pngBytes(), bytes(expect(buyer.get(publicProduct().path("image").asText()), 200)));
            assertEquals(1, fileCount(UPLOAD_DIR));
        });
        add(cases, "AT-029", "公开商品不返回后台字段", () -> {
            createProduct();
            Set<String> actual = new HashSet<>();
            publicProduct().fieldNames().forEachRemaining(actual::add);
            assertEquals(Set.of("id", "name", "description", "image", "priceCents", "status", "buyable"), actual);
        });
        add(cases, "AT-030", "伪造PNG内容拒绝上传", () -> invalidImage("not image".getBytes(StandardCharsets.UTF_8), "fake.png", "image/png"));
        add(cases, "AT-031", "不支持的GIF格式拒绝上传", () -> invalidImage(pngBytes(), "fake.gif", "image/gif"));
        add(cases, "AT-032", "空图片拒绝上传", () -> invalidImage(new byte[0], "empty.png", "image/png"));
        add(cases, "AT-033", "上传文件名不能越出图片目录", () -> {
            loginSeller();
            expect(publish(seller, "2.00", pngBytes(), "../outside.png", "image/png"), 201);
            assertFalse(Files.exists(TEST_ROOT.resolve("outside.png")));
        });
        add(cases, "AT-034", "零元价格拒绝", () -> invalidPrice("0"));
        add(cases, "AT-035", "负数价格拒绝", () -> invalidPrice("-1"));
        add(cases, "AT-036", "三位小数价格拒绝", () -> invalidPrice("1.234"));
        add(cases, "AT-037", "指数格式价格拒绝", () -> invalidPrice("1e2"));
        add(cases, "AT-038", "NaN价格拒绝", () -> invalidPrice("NaN"));
        add(cases, "AT-039", "Infinity价格拒绝", () -> invalidPrice("Infinity"));
        add(cases, "AT-040", "带前导零价格拒绝", () -> invalidPrice("01"));
        add(cases, "AT-041", "空价格拒绝", () -> invalidPrice(""));
        add(cases, "AT-042", "字母价格拒绝", () -> invalidPrice("abc"));
        add(cases, "AT-043", "5000位价格返回400且无副作用", () -> {
            loginSeller();
            String before = sha256(Files.readAllBytes(DB_FILE));
            ApiResponse response = publish(seller, "9".repeat(5000), pngBytes(), "test.png", "image/png");
            assertEquals(400, response.status, response.text());
            assertTrue(response.firstHeader("Content-Type").contains("application/json"));
            assertEquals(before, sha256(Files.readAllBytes(DB_FILE)));
            assertEquals(0, fileCount(UPLOAD_DIR));
            assertTrue(json(expect(buyer.get("/api/product"), 200)).path("product").isNull());
        });
        add(cases, "AT-044", "超过最大金额1分时拒绝", () -> invalidPrice("90071992547409.92"));
        add(cases, "AT-045", "最大允许金额精确保存", () -> {
            loginSeller();
            expect(publish(seller, "90071992547409.91", pngBytes(), "test.png", "image/png"), 201);
            assertEquals(MAX_PRICE_CENTS, publicProduct().path("priceCents").asLong());
        });
        add(cases, "AT-046", "图片超过5MiB拒绝", () -> {
            loginSeller();
            byte[] oversized = Arrays.copyOf(pngBytes(), MAX_IMAGE_BYTES + 1);
            assertTrue(publish(seller, "1", oversized, "large.png", "image/png").status >= 400);
            assertEquals(0, fileCount(UPLOAD_DIR));
        });
        add(cases, "AT-047", "恰好5MiB图片允许发布", () -> {
            loginSeller();
            byte[] boundaryImage = Arrays.copyOf(pngBytes(), MAX_IMAGE_BYTES);
            expect(publish(seller, "1", boundaryImage, "large.png", "image/png"), 201);
            try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
                assertEquals(MAX_IMAGE_BYTES, Files.size(files.findFirst().orElseThrow()));
            }
        });
        add(cases, "AT-048", "买家姓名空白时拒绝意向", () -> {
            createProduct();
            expect(buyer.postJson("/api/intents", object("name", "  ", "phone", "13800000000")), 400);
        });
        add(cases, "AT-049", "买家电话空白时拒绝意向", () -> {
            createProduct();
            expect(buyer.postJson("/api/intents", object("name", "买家", "phone", " ")), 400);
        });
        add(cases, "AT-050", "买家缺少电话时拒绝意向", () -> {
            createProduct();
            expect(buyer.postJson("/api/intents", object("name", "买家")), 400);
        });
        add(cases, "AT-051", "有效买家资料去空格并发放8位口令", () -> {
            createProduct();
            JsonNode result = json(expect(buyer.postJson("/api/intents",
                    object("name", " 买家 ", "phone", " 13800000000 ")), 201));
            assertTrue(result.path("passcode").asText().matches("[A-Z0-9]{8}"));
            assertEquals(1, query(result.path("passcode").asText()).path("position").asInt());
        });
        add(cases, "AT-052", "口令查询不区分大小写", () -> {
            createProduct();
            String code = createIntent("买家", "13800000000");
            assertEquals(1, query(code.toLowerCase()).path("position").asInt());
        });
        add(cases, "AT-053", "相同联系方式的提交保持独立", () -> {
            createProduct();
            List<String> codes = List.of(createIntent("同一买家", "13800000000"),
                    createIntent("同一买家", "13800000000"), createIntent("同一买家", "13800000000"));
            assertEquals(3, new HashSet<>(codes).size());
            List<Integer> positions = new ArrayList<>();
            for (String code : codes) positions.add(query(code).path("position").asInt());
            assertEquals(List.of(1, 2, 3), positions);
        });
        add(cases, "AT-054", "修改联系信息不改变排队位置", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            expect(buyer.putJson("/api/intents", object("passcode", codes.get(1),
                    "name", "新姓名", "phone", "13900000000")), 200);
            JsonNode row = findIntent(codes.get(1));
            assertEquals("新姓名", row.path("name").asText());
            assertEquals("13900000000", row.path("phone").asText());
            assertEquals(2, row.path("position").asInt());
        });
        add(cases, "AT-055", "撤销意向后口令失效", () -> {
            createProduct();
            String code = createIntent("买家", "13800000000");
            expect(buyer.postJson("/api/intents/cancel", object("passcode", code)), 200);
            expect(buyer.postJson("/api/intents/query", object("passcode", code)), 403);
        });
        add(cases, "AT-056", "撤销队首后队列位置递补", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            expect(buyer.postJson("/api/intents/cancel", object("passcode", codes.get(0))), 200);
            assertEquals(1, query(codes.get(1)).path("position").asInt());
        });
        add(cases, "AT-057", "冻结期间不接受新意向", () -> {
            createProduct();
            loginSeller();
            expect(admin("freeze"), 200);
            expect(buyer.postJson("/api/intents", object("name", "买家", "phone", "13800000000")), 409);
        });
        add(cases, "AT-058", "重复冻结返回409", () -> {
            createProduct();
            loginSeller();
            expect(admin("freeze"), 200);
            expect(admin("freeze"), 409);
        });
        add(cases, "AT-059", "解冻后原队列保留且可继续提交", () -> {
            createProduct();
            loginSeller();
            String code = createIntent("买家", "13800000000");
            expect(admin("freeze"), 200);
            expect(admin("unfreeze"), 200);
            assertEquals(1, query(code).path("position").asInt());
            expect(buyer.postJson("/api/intents", object("name", "第二人", "phone", "13900000000")), 201);
        });
        add(cases, "AT-060", "开始交易选择队首意向", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            assertEquals("trading", query(codes.get(0)).path("stage").asText());
            assertEquals("queued", query(codes.get(1)).path("stage").asText());
            assertEquals(1, countTradingIntents());
        });
        add(cases, "AT-061", "成交后商品不再公开销售", () -> {
            createProduct();
            createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            expect(admin("trade/success"), 200);
            assertTrue(json(expect(buyer.get("/api/product"), 200)).path("product").isNull());
        });
        add(cases, "AT-062", "成交后意向结局为成功及未成交", () -> {
            createProduct();
            createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            expect(admin("trade/success"), 200);
            JsonNode history = json(expect(admin("history", "GET", null), 200)).path("products").get(0);
            assertEquals("success", history.path("result").asText());
            assertEquals(List.of("success", "unsold"),
                    StreamSupport.stream(history.path("intents").spliterator(), false)
                            .map(row -> row.path("outcome").asText()).toList());
        });
        add(cases, "AT-063", "交易失败重新排队后旧口令失效", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            JsonNode result = json(expect(admin(seller, "trade/fail", object("disposition", "requeue")), 200));
            assertNotEquals(codes.get(0), result.path("newPasscode").asText());
            expect(buyer.postJson("/api/intents/query", object("passcode", codes.get(0))), 403);
        });
        add(cases, "AT-064", "重新排队生成的新口令进入队尾", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            JsonNode result = json(expect(admin(seller, "trade/fail", object("disposition", "requeue")), 200));
            assertEquals("trading", query(codes.get(1)).path("stage").asText());
            assertEquals(1, query(result.path("newPasscode").asText()).path("position").asInt());
        });
        add(cases, "AT-065", "交易失败作废当前买家并递补下一位", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            expect(admin(seller, "trade/fail", object("disposition", "void")), 200);
            expect(buyer.postJson("/api/intents/query", object("passcode", codes.get(0))), 403);
            assertEquals("trading", query(codes.get(1)).path("stage").asText());
        });
        add(cases, "AT-066", "交易中的买家撤销后不能直接成交", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            expect(buyer.postJson("/api/intents/cancel", object("passcode", codes.get(0))), 200);
            expect(admin("trade/success"), 409);
            assertEquals("queued", query(codes.get(1)).path("stage").asText());
        });
        add(cases, "AT-067", "卖家确认撤销后递补下一位", () -> {
            createProduct();
            List<String> codes = createCodes(2);
            loginSeller();
            expect(admin("trade/start"), 200);
            expect(buyer.postJson("/api/intents/cancel", object("passcode", codes.get(0))), 200);
            expect(admin("trade/advance"), 200);
            assertEquals("trading", query(codes.get(1)).path("stage").asText());
        });
        add(cases, "AT-068", "新应用实例可读取已保存数据和图片", this::verifyRestartPersistence);
        add(cases, "AT-069", "旧数据迁移保留商品ID和内容", () -> {
            importLegacyFixture();
            JsonNode product = publicProduct();
            assertEquals(41, product.path("id").asLong());
            assertEquals("迁移商品", product.path("name").asText());
            assertEquals(1234, product.path("priceCents").asLong());
        });
        add(cases, "AT-070", "旧数据迁移保留意向ID、口令和位次", () -> {
            importLegacyFixture();
            loginSeller();
            JsonNode rows = json(expect(admin("intents", "GET", null), 200)).path("intents");
            assertEquals(1, rows.size());
            assertEquals(73, rows.get(0).path("id").asLong());
            assertEquals("LEGACY42", rows.get(0).path("passcode").asText());
            assertEquals(1, rows.get(0).path("position").asInt());
            assertEquals("queued", query("LEGACY42").path("stage").asText());
        });
        add(cases, "AT-071", "数据迁移不会删除原JSON文件", () -> {
            Path legacy = importLegacyFixture();
            assertTrue(Files.exists(legacy));
        });
        add(cases, "AT-072", "20个并发买家意向均创建成功且口令唯一", () -> {
            createProduct();
            List<ApiResponse> responses = parallel(LongStream.range(0, 20).mapToObj(i ->
                    (Callable<ApiResponse>) () -> new Client(baseUrl, false).postJson("/api/intents",
                            object("name", "买家" + i, "phone", String.format("138%08d", i)))).toList());
            assertEquals(List.of(201), responses.stream().map(r -> r.status).distinct().toList());
            List<String> codes = responses.stream().map(r -> json(r).path("passcode").asText()).toList();
            assertEquals(20, new HashSet<>(codes).size());
        });
        add(cases, "AT-073", "并发创建意向后队列位置连续", () -> {
            createProduct();
            parallel(LongStream.range(0, 20).mapToObj(i ->
                    (Callable<ApiResponse>) () -> new Client(baseUrl, false).postJson("/api/intents",
                            object("name", "买家" + i, "phone", String.format("139%08d", i)))).toList());
            List<Integer> positions = allIntents().findValues("position").stream()
                    .map(JsonNode::asInt).sorted().toList();
            assertEquals(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList(), positions);
        });
        add(cases, "AT-074", "两个卖家并发发布时只创建一个商品", () -> {
            loginSeller();
            Client second = new Client(baseUrl, true);
            expect(login(second, ADMIN_PASSWORD), 200);
            List<ApiResponse> responses = parallel(List.of(
                    () -> publish(seller, "12.50", pngBytes(), "first.png", "image/png"),
                    () -> publish(second, "12.50", pngBytes(), "second.png", "image/png")));
            assertEquals(List.of(201, 409), responses.stream().map(r -> r.status).sorted().toList());
            assertEquals(1, fileCount(UPLOAD_DIR));
        });
        add(cases, "AT-075", "并发开始交易返回200和409", () -> {
            createProduct();
            createCodes(2);
            loginSeller();
            Client second = new Client(baseUrl, true);
            expect(login(second, ADMIN_PASSWORD), 200);
            List<ApiResponse> responses = parallel(List.of(
                    () -> admin("trade/start"), () -> admin(second, "trade/start", object())));
            assertEquals(List.of(200, 409), responses.stream().map(r -> r.status).sorted().toList());
        });
        add(cases, "AT-076", "并发开始交易仅一条意向进入交易中", () -> {
            createProduct();
            createCodes(2);
            loginSeller();
            Client second = new Client(baseUrl, true);
            expect(login(second, ADMIN_PASSWORD), 200);
            parallel(List.of(() -> admin("trade/start"), () -> admin(second, "trade/start", object())));
            assertEquals(1, countTradingIntents());
        });
        add(cases, "AT-077", "截断JSON请求返回400", () -> expect(buyer.raw("POST", "/api/intents", "{"), 400));
        add(cases, "AT-078", "数组JSON请求返回400", () -> expect(buyer.raw("POST", "/api/intents", "[]"), 400));
        add(cases, "AT-079", "null JSON请求返回400", () -> expect(buyer.raw("POST", "/api/intents", "null"), 400));
        add(cases, "AT-080", "字符串JSON请求返回400", () -> expect(buyer.raw("POST", "/api/intents", "\"text\""), 400));
        add(cases, "AT-081", "应用源码路径不对外开放", () -> assertClientError(buyer.get("/app.py")));
        add(cases, "AT-082", "旧数据文件不对外开放", () -> assertClientError(buyer.get("/data.json")));
        add(cases, "AT-083", "Java源码路径不对外开放", () -> assertClientError(buyer.get(
                "/src/main/java/com/coursewise/shopping/ShoppingService.java")));
        add(cases, "AT-084", "上传路径越界读取被拒绝", () -> assertClientError(buyer.get(
                "/uploads/%2e%2e%2fdata%2fshop.db")));
    }

    private void resetForCase() throws Exception {
        baseUrl = "http://127.0.0.1:" + port;
        seller = new Client(baseUrl, true);
        buyer = new Client(baseUrl, false);
        jdbc.update("DELETE FROM intent");
        jdbc.update("DELETE FROM product");
        int sellersUpdated = jdbc.update("UPDATE seller SET password_hash = ?, password_changed_at = NULL",
                ADMIN_HASH);
        if (sellersUpdated == 0) {
            jdbc.update("INSERT INTO seller (username, password_hash, password_changed_at) VALUES (?, ?, NULL)",
                    "admin", ADMIN_HASH);
        }
        Files.createDirectories(UPLOAD_DIR);
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(TEST_ROOT.resolve("legacy.json"));
    }

    private void verifyRestartPersistence() throws Exception {
        JsonNode created = createProduct();
        String imagePath = publicProduct().path("image").asText();
        String passcode = createIntent("买家", "13800000000");
        loginSeller();
        String priorCookie = seller.cookieHeader();
        String jdbcUrl = "jdbc:sqlite:" + DB_FILE;
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ShoppingApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--spring.datasource.url=" + jdbcUrl,
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--shopping.upload-dir=" + UPLOAD_DIR,
                        "--shopping.legacy-data-file=" + MISSING_LEGACY_FILE)) {
            int restartedPort = ((WebServerApplicationContext) restarted).getWebServer().getPort();
            String restartedUrl = "http://127.0.0.1:" + restartedPort;
            Client restartedBuyer = new Client(restartedUrl, false);
            Client restartedSeller = new Client(restartedUrl, true);
            JsonNode product = json(expect(restartedBuyer.get("/api/product"), 200)).path("product");
            assertEquals(created.path("id").asLong(), product.path("id").asLong());
            assertEquals(1, json(expect(restartedBuyer.postJson("/api/intents/query",
                    object("passcode", passcode)), 200)).path("position").asInt());
            assertArrayEquals(pngBytes(), bytes(expect(restartedBuyer.get(imagePath), 200)));
            expect(restartedSeller.request("GET", "/api/admin/product", null, null,
                    Map.of("Cookie", priorCookie)), 401);
            expect(login(restartedSeller, ADMIN_PASSWORD), 200);
            expect(restartedSeller.get("/api/admin/product"), 200);
        }
    }

    private Path importLegacyFixture() throws Exception {
        jdbc.update("DELETE FROM intent");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM seller");
        Path legacy = TEST_ROOT.resolve("legacy.json");
        ObjectNode root = mapper.createObjectNode();
        ObjectNode product = root.putArray("products").addObject();
        product.put("id", 41);
        product.put("name", "迁移商品");
        product.put("description", "legacy");
        product.put("image", "/uploads/legacy.png");
        product.put("priceCents", 1234);
        product.put("status", "active");
        product.putNull("tradingIntentId");
        product.put("createdAt", "2026-10-01T00:00:00Z");
        product.putNull("frozenAt");
        product.putNull("result");
        product.putNull("resultAt");
        ObjectNode intent = root.putArray("intents").addObject();
        intent.put("id", 73);
        intent.put("productId", 41);
        intent.put("name", "历史买家");
        intent.put("phone", "13800000000");
        intent.put("passcode", "LEGACY42");
        intent.put("submittedAt", "2026-10-01T00:01:00Z");
        intent.put("stage", "queued");
        intent.putNull("outcome");
        Files.write(legacy, mapper.writeValueAsBytes(root));
        ApplicationArguments arguments = new DefaultApplicationArguments(new String[0]);
        new DataMigrationRunner(mapper, legacy.toString(), new JdbcTemplate(dataSource),
                products, intents, sellers).run(arguments);
        return legacy;
    }

    private void invalidPassword(String newPassword) throws Exception {
        loginSeller();
        expect(seller.postJson("/api/auth/password",
                object("oldPassword", ADMIN_PASSWORD, "newPassword", newPassword)), 400);
    }

    private void invalidImage(byte[] data, String filename, String mime) throws Exception {
        loginSeller();
        assertTrue(publish(seller, "1", data, filename, mime).status >= 400);
        assertEquals(0, fileCount(UPLOAD_DIR));
    }

    private void invalidPrice(String price) throws Exception {
        loginSeller();
        assertEquals(400, publish(seller, price, pngBytes(), "test.png", "image/png").status);
        assertTrue(json(expect(buyer.get("/api/product"), 200)).path("product").isNull());
        assertEquals(0, fileCount(UPLOAD_DIR));
    }

    private JsonNode createProduct() throws Exception {
        loginSeller();
        expect(publish(seller, "12.50", pngBytes(), "test.png", "image/png"), 201);
        return publicProduct();
    }

    private void loginSeller() throws Exception {
        expect(login(seller, ADMIN_PASSWORD), 200);
    }

    private ApiResponse login(Client client, String password) throws Exception {
        return client.postJson("/api/auth/login", object("username", "admin", "password", password));
    }

    private String createIntent(String name, String phone) throws Exception {
        return json(expect(buyer.postJson("/api/intents", object("name", name, "phone", phone)), 201))
                .path("passcode").asText();
    }

    private List<String> createCodes(int count) throws Exception {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) codes.add(createIntent("买家" + i, "13800000" + String.format("%03d", i)));
        return codes;
    }

    private JsonNode query(String passcode) throws Exception {
        return json(expect(buyer.postJson("/api/intents/query", object("passcode", passcode)), 200));
    }

    private JsonNode publicProduct() throws Exception {
        return json(expect(buyer.get("/api/product"), 200)).path("product");
    }

    private JsonNode allIntents() throws Exception {
        loginSeller();
        return json(expect(admin("intents", "GET", null), 200)).path("intents");
    }

    private JsonNode findIntent(String passcode) throws Exception {
        for (JsonNode row : allIntents()) {
            if (passcode.equals(row.path("passcode").asText())) return row;
        }
        fail("找不到意向 " + passcode);
        return mapper.nullNode();
    }

    private int countTradingIntents() throws Exception {
        int count = 0;
        for (JsonNode row : allIntents()) if ("trading".equals(row.path("stage").asText())) count++;
        return count;
    }

    private ApiResponse admin(String path) throws Exception {
        return admin(seller, path, object());
    }

    private ApiResponse admin(String path, String method, Object payload) throws Exception {
        return admin(seller, path, method, payload);
    }

    private ApiResponse admin(Client client, String path, Object payload) throws Exception {
        return client.postJson("/api/admin/" + path, payload);
    }

    private ApiResponse admin(Client client, String path, String method, Object payload) throws Exception {
        if ("GET".equals(method)) return client.get("/api/admin/" + path);
        return client.postJson("/api/admin/" + path, payload);
    }

    private ApiResponse publish(Client client, String price, byte[] image, String filename, String mime)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, "name", "测试商品");
        field(body, "description", "Java自动化回归");
        field(body, "price", price);
        write(body, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
        body.writeBytes(image);
        write(body, "\r\n--" + BOUNDARY + "--\r\n");
        return client.request("POST", "/api/products", body.toByteArray(),
                "multipart/form-data; boundary=" + BOUNDARY, Map.of());
    }

    private ApiResponse expect(ApiResponse response, int expectedStatus) {
        assertEquals(expectedStatus, response.status,
                "期望HTTP " + expectedStatus + "，实际 " + response.status + "：" + response.text());
        return response;
    }

    private void checkContentType(ApiResponse response, int status, String expectedType) {
        expect(response, status);
        assertTrue(response.firstHeader("Content-Type").contains(expectedType), response.firstHeader("Content-Type"));
    }

    private void assertClientError(ApiResponse response) {
        assertTrue(response.status >= 400, "敏感路径应返回客户端错误，实际HTTP " + response.status);
    }

    private JsonNode json(ApiResponse response) {
        try {
            return mapper.readTree(response.body);
        } catch (IOException e) {
            throw new AssertionError("响应不是有效JSON：" + response.text(), e);
        }
    }

    private static byte[] bytes(ApiResponse response) {
        return response.body;
    }

    private static void field(ByteArrayOutputStream body, String name, String value) {
        write(body, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream stream, String text) {
        stream.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pngBytes() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(header)) {
                data.writeInt(1); data.writeInt(1); data.writeByte(8); data.writeByte(6);
                data.writeByte(0); data.writeByte(0); data.writeByte(0);
            }
            pngChunk(output, "IHDR", header.toByteArray());
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
                deflater.write(new byte[]{0, 0x33, 0x66, (byte) 0x99, (byte) 0xff});
            }
            pngChunk(output, "IDAT", compressed.toByteArray());
            pngChunk(output, "IEND", new byte[0]);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void pngChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        try (DataOutputStream stream = new DataOutputStream(out)) {
            stream.writeInt(data.length);
            stream.write(typeBytes);
            stream.write(data);
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            stream.writeInt((int) crc.getValue());
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static long fileCount(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) { return files.count(); }
    }

    private static List<ApiResponse> parallel(List<Callable<ApiResponse>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (Callable<ApiResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发请求未能同步启动");
                    }
                    return call.call();
                }));
            }
            assertTrue(ready.await(15, java.util.concurrent.TimeUnit.SECONDS), "并发请求未就绪");
            start.countDown();
            List<ApiResponse> responses = new ArrayList<>();
            for (Future<ApiResponse> future : futures) responses.add(future.get(60,
                    java.util.concurrent.TimeUnit.SECONDS));
            return responses;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private static void add(List<RegressionCase> cases, String id, String title, Executable action) {
        cases.add(new RegressionCase(id, title, action));
    }

    private static Map<String, Object> object(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private record RegressionCase(String id, String title, Executable action) { }

    private record ApiResponse(int status, java.net.http.HttpHeaders headers, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
        String firstHeader(String key) { return headers.firstValue(key).orElse(""); }
    }

    private static final class Client {
        private final String baseUrl;
        private final CookieManager cookieManager;
        private final HttpClient http;

        private Client(String baseUrl, boolean retainCookies) {
            this.baseUrl = baseUrl;
            this.cookieManager = retainCookies ? new CookieManager(null, CookiePolicy.ACCEPT_ALL) : null;
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
            if (cookieManager != null) builder.cookieHandler(cookieManager);
            this.http = builder.build();
        }

        ApiResponse get(String path) throws Exception { return request("GET", path, null, null, Map.of()); }

        ApiResponse postJson(String path, Object value) throws Exception {
            return request("POST", path, new ObjectMapper().writeValueAsBytes(value),
                    "application/json", Map.of());
        }

        ApiResponse putJson(String path, Object value) throws Exception {
            return request("PUT", path, new ObjectMapper().writeValueAsBytes(value),
                    "application/json", Map.of());
        }

        ApiResponse raw(String method, String path, String value) throws Exception {
            return request(method, path, value.getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
        }

        ApiResponse request(String method, String path, byte[] body, String contentType,
                            Map<String, String> extraHeaders) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60));
            if (contentType != null) builder.header("Content-Type", contentType);
            extraHeaders.forEach(builder::header);
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
            builder.method(method, publisher);
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new ApiResponse(response.statusCode(), response.headers(), response.body());
        }

        String cookieHeader() {
            if (cookieManager == null) return "";
            return cookieManager.getCookieStore().getCookies().stream()
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .reduce((left, right) -> left + "; " + right).orElse("");
        }
    }

    @org.junit.jupiter.api.AfterAll
    void cleanupTemporaryFiles() throws Exception {
        if (dataSource instanceof HikariDataSource hikari) hikari.close();
        if (Files.exists(TEST_ROOT)) {
            try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
