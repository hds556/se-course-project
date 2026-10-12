package com.coursewise.shopping;

import com.coursewise.shopping.domain.IntentEntity;
import com.coursewise.shopping.domain.IntentOutcome;
import com.coursewise.shopping.domain.IntentStage;
import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.domain.ProductStatus;
import com.coursewise.shopping.domain.SellerEntity;
import com.coursewise.shopping.repository.IntentRepository;
import com.coursewise.shopping.repository.ProductRepository;
import com.coursewise.shopping.repository.SellerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一次性数据迁移：把旧的 {@code data.json} 导入 SQLite。
 *
 * <p>触发条件（任一不满足则跳过，绝不覆盖已有数据）：
 * <ol>
 *   <li>数据库三张表均为空（全新库）；</li>
 *   <li>配置的 legacy 数据文件存在。</li>
 * </ol>
 *
 * <p>迁移内容：卖家账号（密码哈希<b>原样搬运</b>，旧 scrypt 格式仍可登录）、
 * 全部商品、全部意向（含历史商品与完整意向名单）。id 与计数器一并保留，
 * 确保迁移后 id 不重号、历史引用关系不变。
 */
@Component
public class DataMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final ObjectMapper mapper;
    private final Path legacyFile;
    private final ProductRepository products;
    private final IntentRepository intents;
    private final SellerRepository sellers;
    private final SCryptPasswordEncoder passwordEncoder = SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8();

    public DataMigrationRunner(ObjectMapper mapper,
                               @Value("${shopping.legacy-data-file:./data.json}") String legacyFile,
                               ProductRepository products,
                               IntentRepository intents,
                               SellerRepository sellers) {
        this.mapper = mapper;
        this.legacyFile = Path.of(legacyFile).toAbsolutePath().normalize();
        this.products = products;
        this.intents = intents;
        this.sellers = sellers;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        boolean databaseEmpty = sellers.count() == 0 && products.count() == 0 && intents.count() == 0;
        if (!databaseEmpty) {
            ensureSellerExists();
            return;
        }
        if (!Files.exists(legacyFile)) {
            log.info("未发现旧数据文件 {}，按全新数据库初始化", legacyFile);
            ensureSellerExists();
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(legacyFile));
        } catch (Exception e) {
            log.warn("旧数据文件 {} 解析失败，跳过迁移并按全新数据库初始化：{}", legacyFile, e.getMessage());
            ensureSellerExists();
            return;
        }

        int productCount = 0;
        int intentCount = 0;

        JsonNode sellerNode = root.path("seller");
        if (!sellerNode.isMissingNode() && !sellerNode.path("username").asText().isEmpty()) {
            SellerEntity seller = new SellerEntity();
            seller.setUsername(sellerNode.path("username").asText());
            seller.setPasswordHash(sellerNode.path("passwordHash").asText());
            seller.setPasswordChangedAt(sellerNode.path("passwordChangedAt").isNull()
                    ? null : sellerNode.path("passwordChangedAt").asText(null));
            sellers.save(seller);
        }

        for (JsonNode node : root.path("products")) {
            ProductEntity product = new ProductEntity();
            product.setId(longOrNull(node, "id"));
            product.setName(node.path("name").asText());
            product.setDescription(node.path("description").asText());
            product.setImage(node.path("image").asText());
            product.setPriceCents(node.path("priceCents").asLong());
            product.setStatus(ProductStatus.of(node.path("status").asText()));
            product.setTradingIntentId(longOrNull(node, "tradingIntentId"));
            product.setCreatedAt(node.path("createdAt").asText(null));
            product.setFrozenAt(textOrNull(node, "frozenAt"));
            product.setResult(textOrNull(node, "result"));
            product.setResultAt(textOrNull(node, "resultAt"));
            products.save(product);
            productCount++;
        }

        for (JsonNode node : root.path("intents")) {
            IntentEntity intent = new IntentEntity();
            intent.setId(longOrNull(node, "id"));
            intent.setProductId(node.path("productId").asLong());
            intent.setName(node.path("name").asText());
            intent.setPhone(node.path("phone").asText());
            intent.setPasscode(node.path("passcode").asText());
            intent.setSubmittedAt(node.path("submittedAt").asText(null));
            intent.setStage(IntentStage.of(node.path("stage").asText()));
            intent.setOutcome(node.path("outcome").isNull()
                    ? null : IntentOutcome.of(node.path("outcome").asText()));
            intents.save(intent);
            intentCount++;
        }

        log.info("旧数据迁移完成：{} 个商品、{} 条意向、卖家账号 1 个（来源 {}）",
                productCount, intentCount, legacyFile);
        ensureSellerExists();
    }

    /** 无卖家账号时补一个默认账号（初始密码可由环境变量覆盖，BR-18 只存哈希）。 */
    private void ensureSellerExists() {
        if (sellers.count() > 0) return;
        SellerEntity seller = new SellerEntity();
        seller.setUsername("admin");
        seller.setPasswordHash(passwordEncoder.encode(
                System.getenv().getOrDefault("DEFAULT_SELLER_PASSWORD", "admin1234")));
        seller.setPasswordChangedAt(null);
        sellers.save(seller);
        log.info("已创建默认卖家账号 admin（初始密码可由 DEFAULT_SELLER_PASSWORD 环境变量设置）");
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
