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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 业务服务（SRS §3 全部功能需求 + §3.5 业务规则）。
 *
 * <p>持久化已由本地 data.json（Jackson 节点树）改为 <b>SQLite + Spring Data JPA</b>。
 * 业务规则、状态机语义、异常与响应结构均与原实现保持一致；仅存储层不同。
 *
 * <p>写操作统一加 {@code @Transactional}，并在方法内以 {@code synchronized}
 * 串行化关键区，保证"至多一件未售出商品""至多一条交易中意向"（NFR-01）。
 */
@Service
public class ShoppingService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long MAX_PRICE_CENTS = (1L << 53) - 1;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Pattern PRICE_PATTERN = Pattern.compile("^(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?$");
    private static final Pattern PASSWORD_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern PASSWORD_DIGIT = Pattern.compile("\\d");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ObjectMapper mapper;
    private final Path uploadDir;
    private final ProductRepository products;
    private final IntentRepository intents;
    private final SellerRepository sellers;
    private final SCryptPasswordEncoder passwordEncoder = SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final SecureRandom random = new SecureRandom();
    /** 登录会话存内存，重启失效需重新登录（对应设计文档 §4.2-6）。 */
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    /** Serializes trade starts through transaction commit, not only through method return. */
    private final ReentrantLock tradeStartLock = new ReentrantLock(true);

    public ShoppingService(ObjectMapper mapper,
                           ProductRepository products,
                           IntentRepository intents,
                           SellerRepository sellers,
                           @Value("${shopping.upload-dir}") String uploadDir) {
        this.mapper = mapper;
        this.products = products;
        this.intents = intents;
        this.sellers = sellers;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化图片目录", e);
        }
    }

    // ==================== 买家侧（FR-B-01 ~ FR-B-05）====================

    /** FR-B-01 浏览在售商品。 */
    @Transactional(readOnly = true)
    public synchronized ObjectNode publicProduct() {
        ObjectNode result = mapper.createObjectNode();
        ProductEntity product = currentProduct();
        if (product == null) {
            result.putNull("product");
            return result;
        }
        result.set("product", productView(product));
        return result;
    }

    /** FR-B-02 提交购买意向：只登记排队，不冻结商品（BR-01）。 */
    @Transactional
    public synchronized ObjectNode createIntent(String name, String phone) {
        ProductEntity product = requireBuyableProduct();
        requirePerson(name, phone);
        IntentEntity intent = new IntentEntity();
        intent.setProductId(product.getId());
        intent.setName(name.trim());
        intent.setPhone(phone.trim());
        intent.setPasscode(newPasscode());
        intent.setSubmittedAt(now());
        intent.setStage(IntentStage.queued);
        intent.setOutcome(null);
        intents.save(intent);
        return mapper.createObjectNode().put("passcode", intent.getPasscode());
    }

    /** FR-B-03 凭口令码查询意向。 */
    @Transactional(readOnly = true)
    public synchronized ObjectNode queryIntent(String passcode) {
        IntentEntity intent = activeIntent(passcode);
        ObjectNode response = mapper.createObjectNode();
        response.put("stage", intent.getStage().code());
        if (intent.isQueued()) {
            response.put("position", queuePosition(intent));
        } else {
            response.putNull("position");
        }
        return response;
    }

    /** FR-B-04 凭口令码修改姓名/电话；不改变位次（BR-12）。 */
    @Transactional
    public synchronized void updateIntent(String passcode, String name, String phone) {
        IntentEntity intent = activeIntent(passcode);
        requirePerson(name, phone);
        intent.setName(name.trim());
        intent.setPhone(phone.trim());
        intents.save(intent);
    }

    /** FR-B-05 凭口令码撤销意向；不改变商品状态（BR-09）。 */
    @Transactional
    public synchronized void cancelIntent(String passcode) {
        IntentEntity intent = activeIntent(passcode);
        intent.setStage(IntentStage.closed);
        intent.setOutcome(IntentOutcome.cancelled);
        intents.save(intent);
    }

    // ==================== 卖家认证（FR-S-01、FR-S-09）====================

    /** FR-S-01 卖家登录；失败提示不区分账号/密码错误。 */
    @Transactional(readOnly = true)
    public synchronized boolean login(String username, String password, String session) {
        SellerEntity seller = sellers.findByUsername(username == null ? "" : username).orElse(null);
        if (seller == null || !matchesPassword(password, seller.getPasswordHash())) {
            return false;
        }
        sessions.add(session);
        return true;
    }

    /** FR-S-09 修改密码；验原密码，新密码 ≥ 8 位且含字母和数字（BR-18）。 */
    @Transactional
    public synchronized void changePassword(String session, String oldPassword, String newPassword) {
        requireSession(session);
        SellerEntity seller = requireSeller();
        if (!matchesPassword(oldPassword, seller.getPasswordHash())) {
            throw error(HttpStatus.BAD_REQUEST, "原密码错误");
        }
        if (newPassword == null || newPassword.length() < 8
                || !PASSWORD_LETTER.matcher(newPassword).find()
                || !PASSWORD_DIGIT.matcher(newPassword).find()) {
            throw error(HttpStatus.BAD_REQUEST, "新密码至少 8 位且必须同时包含字母和数字");
        }
        seller.setPasswordHash(passwordEncoder.encode(newPassword));
        seller.setPasswordChangedAt(now());
        sellers.save(seller);
    }

    // ==================== 卖家后台（FR-S-02 ~ FR-S-08）====================

    /** FR-S-02 发布商品；系统至多一件未售出商品。 */
    @Transactional
    public synchronized ObjectNode createProduct(String session, String name, String description,
                                                 String price, MultipartFile image) {
        requireSession(session);
        if (currentProduct() != null) throw error(HttpStatus.CONFLICT, "当前已有未售出商品");
        if (image == null || image.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "必须上传 JPG 或 PNG 图片");
        ImageData checked = validateImage(image);
        long cents = parsePrice(price);
        if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "商品名称、描述和有效价格均为必填");
        }
        String filename = "p-" + System.currentTimeMillis() + "-" + randomHex(6) + checked.extension();
        Path target = uploadDir.resolve(filename).normalize();
        try {
            if (!target.getParent().equals(uploadDir)) throw new IOException("非法文件路径");
            Files.write(target, checked.content(), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败", e);
        }
        ProductEntity product = new ProductEntity();
        product.setName(name.trim());
        product.setDescription(description.trim());
        product.setImage("/uploads/" + filename);
        product.setPriceCents(cents);
        product.setStatus(ProductStatus.active);
        product.setTradingIntentId(null);
        product.setCreatedAt(now());
        product.setFrozenAt(null);
        product.setResult(null);
        product.setResultAt(null);
        try {
            products.save(product);
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw e;
        }
        return mapper.createObjectNode().put("id", product.getId());
    }

    /** 后台当前商品视图（含交易中意向）。 */
    @Transactional(readOnly = true)
    public synchronized ObjectNode adminProduct(String session) {
        requireSession(session);
        ObjectNode result = mapper.createObjectNode();
        ProductEntity product = currentProduct();
        if (product == null) {
            result.putNull("product");
            result.putNull("tradingIntent");
            return result;
        }
        result.set("product", fullProductView(product));
        IntentEntity trading = tradingIntent(product);
        result.set("tradingIntent", trading == null ? mapper.nullNode() : intentView(trading, product));
        return result;
    }

    /** FR-S-03 查看意向队列（仅卖家可见）。 */
    @Transactional(readOnly = true)
    public synchronized ObjectNode adminIntents(String session) {
        requireSession(session);
        ArrayNode records = mapper.createArrayNode();
        ProductEntity product = currentProduct();
        if (product != null) {
            for (IntentEntity intent : queueOrder(product.getId())) {
                boolean isTradingCancelled = intent.isClosed()
                        && intent.getOutcome() == IntentOutcome.cancelled
                        && Objects.equals(intent.getId(), product.getTradingIntentId());
                // 已终结的普通意向不再列出；仅保留"交易中买家已撤销"这一条待处理标记
                if (!intent.isClosed() || isTradingCancelled) {
                    records.add(intentView(intent, product));
                }
            }
        }
        return mapper.createObjectNode().set("intents", records);
    }

    /** FR-S-05 手动冻结。 */
    @Transactional
    public synchronized void freeze(String session) {
        requireSession(session);
        ProductEntity product = currentProduct();
        if (product == null || !product.isBuyable()) {
            throw error(HttpStatus.CONFLICT, "当前商品不能手动冻结");
        }
        product.setStatus(ProductStatus.frozen);
        product.setFrozenAt(now());
        product.setTradingIntentId(null);
        products.save(product);
    }

    /** FR-S-05 手动解冻：只改商品状态，不触碰任何意向（BR-06）。 */
    @Transactional
    public synchronized void unfreeze(String session) {
        requireSession(session);
        ProductEntity product = currentProduct();
        if (product == null || product.getStatus() != ProductStatus.frozen || product.getTradingIntentId() != null) {
            throw error(HttpStatus.CONFLICT, "当前商品不能手动解冻");
        }
        product.setStatus(ProductStatus.active);
        products.save(product);
    }

    /** FR-S-04 开始交易：强制取队首，不提供跳选（BR-04）。 */
    @Transactional
    public synchronized ObjectNode startTrade(String session) {
        tradeStartLock.lock();
        boolean releaseAfterCompletion = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        tradeStartLock.unlock();
                    }
                });
                releaseAfterCompletion = true;
            }
        } finally {
            if (!releaseAfterCompletion) tradeStartLock.unlock();
        }
        requireSession(session);
        ProductEntity product = currentProduct();
        if (product == null || !product.isBuyable()) {
            throw error(HttpStatus.CONFLICT, "商品当前不能开始交易");
        }
        IntentEntity next = startNextTrade(product);
        if (next == null) throw error(HttpStatus.CONFLICT, "当前没有排队中的购买意向");
        ObjectNode result = mapper.createObjectNode();
        result.put("id", next.getId());
        result.put("name", next.getName());
        result.put("phone", next.getPhone());
        result.put("intentId", next.getId());
        return result;
    }

    /** FR-S-06 标记交易成功：下架终态、其余记未成交、口令码全部失效（BR-08）。 */
    @Transactional
    public synchronized void tradeSuccess(String session) {
        requireSession(session);
        ProductEntity product = currentProduct();
        IntentEntity buyer = tradingIntent(product);
        if (buyer == null || buyer.getStage() != IntentStage.trading) {
            throw error(HttpStatus.CONFLICT, "当前没有交易中的意向");
        }
        buyer.setStage(IntentStage.closed);
        buyer.setOutcome(IntentOutcome.success);
        intents.save(buyer);
        for (IntentEntity item : allIntents(product.getId())) {
            if (item.getStage() != IntentStage.closed) {
                item.setStage(IntentStage.closed);
                item.setOutcome(IntentOutcome.unsold);
                intents.save(item);
            }
        }
        product.setStatus(ProductStatus.sold);
        product.setResult("success");
        product.setResultAt(now());
        product.setTradingIntentId(null);
        products.save(product);
    }

    /** FR-S-07 标记交易失败：处置当前买家（作废/重新排队）后立即分流（BR-05、BR-07）。 */
    @Transactional
    public synchronized ObjectNode tradeFail(String session, String disposition) {
        requireSession(session);
        if (!"void".equals(disposition) && !"requeue".equals(disposition)) {
            throw error(HttpStatus.BAD_REQUEST, "无效的失败处置方式");
        }
        ProductEntity product = currentProduct();
        IntentEntity buyer = tradingIntent(product);
        if (buyer == null || buyer.getStage() != IntentStage.trading) {
            throw error(HttpStatus.CONFLICT, "当前没有交易中的意向");
        }
        buyer.setStage(IntentStage.closed);
        ObjectNode result = mapper.createObjectNode().put("ok", true);
        if ("requeue".equals(disposition)) {
            buyer.setOutcome(IntentOutcome.requeued);
            intents.save(buyer);
            IntentEntity replacement = new IntentEntity();
            replacement.setProductId(buyer.getProductId());
            replacement.setName(buyer.getName());
            replacement.setPhone(buyer.getPhone());
            replacement.setPasscode(newPasscode());
            replacement.setSubmittedAt(now());
            replacement.setStage(IntentStage.queued);
            replacement.setOutcome(null);
            intents.save(replacement);
            result.put("newPasscode", replacement.getPasscode());
        } else {
            buyer.setOutcome(IntentOutcome.voided);
            intents.save(buyer);
            result.putNull("newPasscode");
        }
        product.setResultAt(now());
        product.setTradingIntentId(null);
        products.save(product);
        IntentEntity next = startNextTrade(product);
        result.put("nextIntentId", next == null ? null : next.getId());
        return result;
    }

    /** 交易中买家自行撤销后，卖家确认处理并递补（BR-09 的后续动作）。 */
    @Transactional
    public synchronized ObjectNode advanceCancelled(String session) {
        requireSession(session);
        ProductEntity product = currentProduct();
        IntentEntity cancelled = tradingIntent(product);
        if (product == null || product.getStatus() != ProductStatus.frozen || cancelled == null
                || cancelled.getStage() != IntentStage.closed
                || cancelled.getOutcome() != IntentOutcome.cancelled) {
            throw error(HttpStatus.CONFLICT, "当前没有待递补的已撤销交易意向");
        }
        product.setTradingIntentId(null);
        products.save(product);
        IntentEntity next = startNextTrade(product);
        return mapper.createObjectNode().put("ok", true)
                .put("nextIntentId", next == null ? null : next.getId());
    }

    /** FR-S-08 查看历史商品（含完整意向名单，BR-15）。 */
    @Transactional(readOnly = true)
    public synchronized ObjectNode history(String session) {
        requireSession(session);
        ArrayNode history = mapper.createArrayNode();
        for (ProductEntity product : products.findByStatusOrderByIdDesc(ProductStatus.sold)) {
            ObjectNode copy = fullProductView(product);
            ArrayNode records = mapper.createArrayNode();
            for (IntentEntity intent : allIntents(product.getId())) {
                records.add(intentView(intent, product));
            }
            copy.set("intents", records);
            history.add(copy);
        }
        return mapper.createObjectNode().set("products", history);
    }

    // ==================== 会话 ====================

    public void requireSession(String session) {
        if (session == null || !sessions.contains(session)) throw error(HttpStatus.UNAUTHORIZED, "未登录");
    }

    // ==================== 内部实现 ====================

    /**
     * 凭口令码取"仍然有效"的意向（BR-13）：
     * 意向未终结，且其商品尚未下架。无效与已失效不作区分（FR-B-03）。
     */
    private IntentEntity activeIntent(String passcode) {
        String normalized = passcode == null ? "" : passcode.trim().toUpperCase(Locale.ROOT);
        IntentEntity intent = intents.findByPasscode(normalized).orElse(null);
        if (intent == null || intent.isClosed()) {
            throw error(HttpStatus.FORBIDDEN, "口令码无效或已失效");
        }
        ProductEntity product = products.findById(intent.getProductId()).orElse(null);
        if (product == null || product.getStatus() == ProductStatus.sold) {
            throw error(HttpStatus.FORBIDDEN, "口令码无效或已失效");
        }
        return intent;
    }

    private ProductEntity requireBuyableProduct() {
        ProductEntity product = currentProduct();
        if (product == null || !product.isBuyable()) {
            throw error(HttpStatus.CONFLICT, "商品当前不接受购买意向");
        }
        return product;
    }

    private SellerEntity requireSeller() {
        return sellers.findTheSeller()
                .orElseThrow(() -> new IllegalStateException("卖家账号不存在"));
    }

    /** 当前未售出商品（至多一件）。 */
    private ProductEntity currentProduct() {
        return products.findFirstByStatusNotOrderByIdDesc(ProductStatus.sold).orElse(null);
    }

    /** 当前"交易中"的意向；无绑定则返回 null。 */
    private IntentEntity tradingIntent(ProductEntity product) {
        if (product == null || product.getTradingIntentId() == null) return null;
        return intents.findById(product.getTradingIntentId()).orElse(null);
    }

    private List<IntentEntity> queueOrder(Long productId) {
        return intents.findByProductIdOrderBySubmittedAtAscIdAsc(productId);
    }

    private List<IntentEntity> allIntents(Long productId) {
        return intents.findByProductIdOrderBySubmittedAtAscIdAsc(productId);
    }

    /**
     * 取队首开始交易（BR-04）：严格按 (submittedAt, id) 升序的第一条 queued。
     * 队列非空 → 商品冻结 + 该意向转交易中；队列已空 → 商品恢复在售。
     */
    private IntentEntity startNextTrade(ProductEntity product) {
        List<IntentEntity> queued = intents.findByProductIdAndStageOrderBySubmittedAtAscIdAsc(
                product.getId(), IntentStage.queued);
        if (!queued.isEmpty()) {
            IntentEntity first = queued.get(0);
            product.setStatus(ProductStatus.frozen);
            product.setFrozenAt(now());
            product.setTradingIntentId(first.getId());
            products.save(product);
            first.setStage(IntentStage.trading);
            intents.save(first);
            return first;
        }
        product.setStatus(ProductStatus.resumed);
        product.setTradingIntentId(null);
        products.save(product);
        return null;
    }

    /** 位次 = 同商品下 stage=queued 且排序在前的条数 + 1（不落库）。 */
    private int queuePosition(IntentEntity intent) {
        List<IntentEntity> queued = intents.findByProductIdAndStageOrderBySubmittedAtAscIdAsc(
                intent.getProductId(), IntentStage.queued);
        for (int i = 0; i < queued.size(); i++) {
            if (Objects.equals(queued.get(i).getId(), intent.getId())) return i + 1;
        }
        return 0;
    }

    /** 生成唯一口令码；数据库唯一约束兜底。 */
    private String newPasscode() {
        StringBuilder code = new StringBuilder();
        do {
            code.setLength(0);
            for (int i = 0; i < 8; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        } while (intents.existsByPasscode(code.toString()));
        return code.toString();
    }

    // ==================== 视图组装（保持原 JSON 结构）====================

    /** 买家侧商品视图：仅暴露公开字段 + buyable。 */
    private ObjectNode productView(ProductEntity product) {
        ObjectNode view = mapper.createObjectNode();
        view.put("id", product.getId());
        view.put("name", product.getName());
        view.put("description", product.getDescription());
        view.put("image", product.getImage());
        view.put("priceCents", product.getPriceCents());
        view.put("status", product.getStatus().code());
        view.put("buyable", product.isBuyable());
        return view;
    }

    /** 后台商品视图：商品全字段。 */
    private ObjectNode fullProductView(ProductEntity product) {
        ObjectNode view = mapper.createObjectNode();
        view.put("id", product.getId());
        view.put("name", product.getName());
        view.put("description", product.getDescription());
        view.put("image", product.getImage());
        view.put("priceCents", product.getPriceCents());
        view.put("status", product.getStatus().code());
        view.put("createdAt", product.getCreatedAt());
        putNullable(view, "frozenAt", product.getFrozenAt());
        putNullable(view, "result", product.getResult());
        putNullable(view, "resultAt", product.getResultAt());
        return view;
    }

    /** 意向视图：含计算出的 position。 */
    private ObjectNode intentView(IntentEntity intent, ProductEntity product) {
        ObjectNode view = mapper.createObjectNode();
        view.put("id", intent.getId());
        view.put("productId", intent.getProductId());
        view.put("name", intent.getName());
        view.put("phone", intent.getPhone());
        view.put("passcode", intent.getPasscode());
        view.put("submittedAt", intent.getSubmittedAt());
        view.put("stage", intent.getStage().code());
        if (intent.getOutcome() == null) {
            view.putNull("outcome");
        } else {
            view.put("outcome", intent.getOutcome().code());
        }
        if (intent.isQueued()) {
            view.put("position", queuePosition(intent));
        } else {
            view.putNull("position");
        }
        return view;
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    // ==================== 校验与工具 ====================

    private ImageData validateImage(MultipartFile file) {
        if (file.getSize() > MAX_IMAGE_BYTES
                || (!"image/jpeg".equals(file.getContentType()) && !"image/png".equals(file.getContentType()))) {
            throw error(HttpStatus.BAD_REQUEST, "图片必须为 JPG/PNG 且不超过 5MB");
        }
        try {
            byte[] content = file.getBytes();
            try (InputStream input = file.getInputStream()) {
                BufferedImage image = ImageIO.read(input);
                if (image == null) throw new IOException("无法识别图片");
            }
            return new ImageData(content, file.getContentType().equals("image/jpeg") ? ".jpg" : ".png");
        } catch (IOException | NoSuchElementException e) {
            throw error(HttpStatus.BAD_REQUEST, "图片必须为 JPG/PNG 且不超过 5MB");
        }
    }

    /** 价格解析：整数元/分拆分，上限 9007199254740991 分（BR-17）。 */
    private long parsePrice(String value) {
        if (value == null || value.length() > 18 || !PRICE_PATTERN.matcher(value.trim()).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "商品名称、描述和有效价格均为必填");
        }
        try {
            String[] parts = value.trim().split("\\.", -1);
            long cents = Math.addExact(Math.multiplyExact(Long.parseLong(parts[0]), 100),
                    parts.length == 2 ? Long.parseLong((parts[1] + "00").substring(0, 2)) : 0);
            if (cents <= 0 || cents > MAX_PRICE_CENTS) throw new ArithmeticException();
            return cents;
        } catch (ArithmeticException | NumberFormatException e) {
            throw error(HttpStatus.BAD_REQUEST, "商品名称、描述和有效价格均为必填");
        }
    }

    private void requirePerson(String name, String phone) {
        if (name == null || name.trim().isEmpty() || phone == null || phone.trim().isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "姓名和联系电话均为必填");
        }
    }

    /** 兼容两种哈希格式：scrypt:&lt;salt&gt;:&lt;hex&gt;（旧数据）与 Spring Security 编码。 */
    private boolean matchesPassword(String password, String encoded) {
        String plain = password == null ? "" : password;
        if (encoded == null) return false;
        try {
            if (encoded.startsWith("scrypt:")) {
                String[] parts = encoded.split(":", -1);
                if (parts.length != 3) return false;
                byte[] expected = hex(parts[2]);
                byte[] salt = parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] actual = SCrypt.generate(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        salt, 16384, 8, 1, 64);
                return java.security.MessageDigest.isEqual(actual, expected);
            }
            return passwordEncoder.matches(plain, encoded);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] hex(String value) {
        if (value.length() % 2 != 0) throw new IllegalArgumentException("invalid hash");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item));
        return result.toString();
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private record ImageData(byte[] content, String extension) {}
}
