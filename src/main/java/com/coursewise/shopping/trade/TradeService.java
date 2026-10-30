package com.coursewise.shopping.trade;

import com.coursewise.shopping.ShoppingService;
import com.coursewise.shopping.catalog.CatalogException;
import com.coursewise.shopping.customer.CustomerService;
import com.coursewise.shopping.domain.CustomerEntity;
import com.coursewise.shopping.domain.IntentEntity;
import com.coursewise.shopping.domain.IntentOutcome;
import com.coursewise.shopping.domain.IntentStage;
import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.domain.ProductStatus;
import com.coursewise.shopping.repository.CustomerRepository;
import com.coursewise.shopping.repository.IntentRepository;
import com.coursewise.shopping.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * V2.0 交易模块：购买意向、发起交易、标记成功/失败、手动下架、售罄补货与重新上架。
 *
 * <p>对应任务（禅道）：22303/22304 提交购买意向、22271/22272 发起交易、
 * 22273/22274 标记交易成功、22275/22276 标记交易失败、22267/22268 手动下架、
 * 22269/22270 售罄补货并重新上架。
 *
 * <p>状态机（SRS V2.0 §3.3）：
 * <pre>
 *   active/resumed --发起交易--> frozen --标记成功--> active（库存仍 >0）
 *                                      \--库存归零--> sold（终态，进历史）
 *                                      \--标记失败--> active（result=fail，进历史）
 *   active/resumed/frozen --手动下架--> withdrawn（终态，不进历史，无重新上架）
 *   active/resumed --库存调 0--> depleted（售罄）--补货--> depleted --重新上架--> active
 * </pre>
 *
 * <p>所有写操作共用一把可重入锁 + 事务模板，保证"一件商品同一时刻至多一条交易中意向"。
 */
@Service
public class TradeService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ProductRepository products;
    private final IntentRepository intents;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final ShoppingService auth;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    /** 覆盖事务提交范围的写锁。 */
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final SecureRandom random = new SecureRandom();

    public TradeService(ProductRepository products, IntentRepository intents, CustomerRepository customers,
                        CustomerService customerService, ShoppingService auth, ObjectMapper mapper,
                        PlatformTransactionManager manager) {
        this.products = products;
        this.intents = intents;
        this.customers = customers;
        this.customerService = customerService;
        this.auth = auth;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    // ==================== 买家：提交购买意向（22303 / 22304）====================

    /**
     * 提交购买意向：一单固定 1 件，只登记排队，<b>不预占不扣减库存</b>。
     * 意向信息直接取自登录账号（BR-16），不落冗余姓名/电话以外的可修改字段。
     */
    public ObjectNode submitIntent(String session, Long productId) {
        return write(() -> {
            CustomerEntity customer = customerService.requireCustomer(session);
            if (productId == null) throw bad("productId", "商品参数无效");
            ProductEntity product = products.findById(productId).orElseThrow(this::missing);
            if (!product.isBuyable() || product.getStock() == null || product.getStock() <= 0) {
                throw conflict("productId", "商品当前不接受购买意向");
            }
            IntentEntity intent = new IntentEntity();
            intent.setProductId(product.getId());
            intent.setCustomerId(customer.getId());
            intent.setName(customer.getUsername());
            intent.setPhone(customer.getPhone());
            intent.setPasscode(newPasscode());
            intent.setSubmittedAt(now());
            intent.setStage(IntentStage.queued);
            intent.setOutcome(null);
            intents.saveAndFlush(intent);

            ObjectNode result = mapper.createObjectNode();
            result.put("id", intent.getId());
            result.put("productId", product.getId());
            result.put("submittedAt", intent.getSubmittedAt());
            result.put("position", position(product.getId(), intent.getId()));
            // 回显库存，供"提交后库存数字不变"的验收核对
            result.put("stock", product.getStock());
            return result;
        });
    }

    /** 当前客户的进行中下单记录（排队中 / 交易中），供"我的订单"页展示。 */
    public ObjectNode myIntents(String session) {
        return write(() -> {
            CustomerEntity customer = customerService.requireCustomer(session);
            List<IntentEntity> mine = intents.findByCustomerIdAndStageInOrderBySubmittedAtDescIdDesc(
                    customer.getId(), List.of(IntentStage.queued, IntentStage.trading));
            ArrayNode records = mapper.createArrayNode();
            for (IntentEntity intent : mine) {
                ProductEntity product = products.findById(intent.getProductId()).orElse(null);
                if (product == null) continue;
                ObjectNode item = mapper.createObjectNode();
                item.put("id", intent.getId());
                item.put("productId", product.getId());
                item.put("productName", product.getName());
                item.put("submittedAt", intent.getSubmittedAt());
                item.put("stage", intent.getStage().code());
                if (intent.isQueued()) item.put("position", position(product.getId(), intent.getId()));
                else item.putNull("position");
                records.add(item);
            }
            return mapper.createObjectNode().set("items", records);
        });
    }

    // ==================== 卖家：交易工作台 ====================

    /** 交易工作台：在售/冻结/售罄商品 + 各自队列。 */
    public ArrayNode workbench(String session) {
        return write(() -> {
            auth.requireSession(session);
            List<ProductEntity> list = products.findByStatusInOrderByIdDesc(
                    List.of(ProductStatus.active, ProductStatus.resumed, ProductStatus.frozen, ProductStatus.depleted));
            ArrayNode result = mapper.createArrayNode();
            for (ProductEntity product : list) result.add(workbenchItem(product));
            return result;
        });
    }

    /** 单个商品的交易视图（含队列明细）。 */
    public ObjectNode workbenchItem(long productId, String session) {
        return write(() -> {
            auth.requireSession(session);
            return workbenchItem(requireProduct(productId));
        });
    }

    // ==================== 卖家：发起交易（22271 / 22272）====================

    /**
     * 发起交易：强制取队首（最早提交且未终结），不提供跳选；
     * 商品随即冻结，停止接受新意向，且只影响这一件商品。
     */
    public ObjectNode startTrade(String session, long productId) {
        return write(() -> {
            auth.requireSession(session);
            ProductEntity product = requireProduct(productId);
            if (product.getStatus() != ProductStatus.active && product.getStatus() != ProductStatus.resumed) {
                throw conflict("productId", "商品当前不能开始交易");
            }
            if (product.getStock() == null || product.getStock() <= 0) {
                throw conflict("productId", "库存为 0 的商品不能发起交易");
            }
            List<IntentEntity> queued = intents.findByProductIdAndStageOrderBySubmittedAtAscIdAsc(
                    product.getId(), IntentStage.queued);
            if (queued.isEmpty()) throw conflict("productId", "当前没有排队中的购买意向");

            IntentEntity first = queued.get(0);
            product.setStatus(ProductStatus.frozen);
            product.setFrozenAt(now());
            product.setTradingIntentId(first.getId());
            products.saveAndFlush(product);
            first.setStage(IntentStage.trading);
            intents.saveAndFlush(first);

            ObjectNode result = mapper.createObjectNode();
            result.put("productId", product.getId());
            result.put("intentId", first.getId());
            result.put("name", first.getName());
            result.put("phone", first.getPhone());
            result.put("submittedAt", first.getSubmittedAt());
            if (first.getCustomerId() != null) result.put("customerId", first.getCustomerId());
            return result;
        });
    }

    // ==================== 卖家：标记交易成功（22273 / 22274）====================

    /**
     * 标记交易成功：库存减 1；仍 >0 则回在售，=0 则进入已售终态（补货不恢复）；
     * 同一客户在该商品的其余未终结意向一并作废（记失败）；不得超卖。
     */
    public ObjectNode markSuccess(String session, long productId) {
        return write(() -> {
            auth.requireSession(session);
            ProductEntity product = requireProduct(productId);
            IntentEntity buyer = tradingIntent(product);
            if (buyer == null || buyer.getStage() != IntentStage.trading) {
                throw conflict("productId", "当前没有交易中的意向");
            }
            if (product.getStock() == null || product.getStock() <= 0) {
                throw conflict("productId", "库存为 0，不能成交");
            }
            String at = now();
            int remaining = product.getStock() - 1;

            buyer.setStage(IntentStage.closed);
            buyer.setOutcome(IntentOutcome.success);
            buyer.setClosedAt(at);
            intents.saveAndFlush(buyer);

            // 同一客户的其余未终结意向：一并作废（客户历史中记为失败）
            for (IntentEntity other : allIntents(product.getId())) {
                if (Objects.equals(other.getId(), buyer.getId())) continue;
                if (other.getStage() == IntentStage.closed) continue;
                if (Objects.equals(other.getCustomerId(), buyer.getCustomerId())) {
                    other.setStage(IntentStage.closed);
                    other.setOutcome(IntentOutcome.voided);
                    other.setClosedAt(at);
                    intents.saveAndFlush(other);
                }
            }

            product.setStock(remaining);
            product.setResult("success");
            product.setResultAt(at);
            product.setTradingIntentId(null);
            if (remaining > 0) {
                product.setStatus(ProductStatus.active);
            } else {
                // 售罄：终态，其余未终结意向全部记未成交
                product.setStatus(ProductStatus.sold);
                for (IntentEntity other : allIntents(product.getId())) {
                    if (other.getStage() == IntentStage.closed) continue;
                    other.setStage(IntentStage.closed);
                    other.setOutcome(IntentOutcome.unsold);
                    other.setClosedAt(at);
                    intents.saveAndFlush(other);
                }
            }
            products.saveAndFlush(product);

            ObjectNode result = mapper.createObjectNode();
            result.put("ok", true);
            result.put("stock", remaining);
            result.put("status", product.getStatus().code());
            result.put("resultAt", at);
            return result;
        });
    }

    // ==================== 卖家：标记交易失败（22275 / 22276）====================

    /**
     * 标记交易失败：不扣库存；商品恢复在售；<b>不自动递补下一位</b>，需卖家再次发起。
     * 处置：作废 → 该意向终态 voided；重新排队 → 旧记录 requeued，新记录入队尾、下单时间刷新。
     */
    public ObjectNode markFail(String session, long productId, String disposition) {
        return write(() -> {
            auth.requireSession(session);
            if (!"void".equals(disposition) && !"requeue".equals(disposition)) {
                throw bad("disposition", "无效的失败处置方式");
            }
            ProductEntity product = requireProduct(productId);
            IntentEntity buyer = tradingIntent(product);
            if (buyer == null || buyer.getStage() != IntentStage.trading) {
                throw conflict("productId", "当前没有交易中的意向");
            }
            String at = now();
            buyer.setStage(IntentStage.closed);
            buyer.setClosedAt(at);

            ObjectNode result = mapper.createObjectNode().put("ok", true);
            if ("requeue".equals(disposition)) {
                buyer.setOutcome(IntentOutcome.requeued);
                intents.saveAndFlush(buyer);
                IntentEntity again = new IntentEntity();
                again.setProductId(buyer.getProductId());
                again.setCustomerId(buyer.getCustomerId());
                again.setName(buyer.getName());
                again.setPhone(buyer.getPhone());
                again.setPasscode(newPasscode());
                again.setSubmittedAt(at);
                again.setStage(IntentStage.queued);
                again.setOutcome(null);
                intents.saveAndFlush(again);
                result.put("newIntentId", again.getId());
                result.put("position", position(product.getId(), again.getId()));
            } else {
                buyer.setOutcome(IntentOutcome.voided);
                intents.saveAndFlush(buyer);
                result.putNull("newIntentId");
            }

            product.setStatus(ProductStatus.active);
            product.setTradingIntentId(null);
            product.setFrozenAt(product.getFrozenAt());
            // 发生过交易失败的商品计入历史商品列表
            product.setResult("fail");
            product.setResultAt(at);
            products.saveAndFlush(product);

            result.put("stock", product.getStock());
            result.put("status", product.getStatus().code());
            result.put("resultAt", at);
            return result;
        });
    }

    // ==================== 卖家：手动下架（22267 / 22268）====================

    /**
     * 手动下架：在售或已冻结商品都可下架；下架后买家端立即不可见、不可访问、不可提交意向。
     * 未终结意向（队列中 + 交易中）按卖家选择统一处置：作废 / 重新排队。
     * 手动下架为终态，不进入历史商品列表，也不提供重新上架入口。
     */
    public ObjectNode withdraw(String session, long productId, String disposition) {
        return write(() -> {
            auth.requireSession(session);
            if (!"void".equals(disposition) && !"requeue".equals(disposition)) {
                throw bad("disposition", "请对未终结意向选择作废或重新排队");
            }
            ProductEntity product = requireProduct(productId);
            if (product.getStatus() != ProductStatus.active && product.getStatus() != ProductStatus.resumed
                    && product.getStatus() != ProductStatus.frozen) {
                throw conflict("productId", "当前商品不能手动下架");
            }
            String at = now();
            int handled = 0;
            for (IntentEntity intent : allIntents(product.getId())) {
                if (intent.getStage() == IntentStage.closed) continue;
                handled++;
                intent.setStage(IntentStage.closed);
                intent.setClosedAt(at);
                if ("requeue".equals(disposition)) {
                    intent.setOutcome(IntentOutcome.requeued);
                    intents.saveAndFlush(intent);
                    IntentEntity again = new IntentEntity();
                    again.setProductId(intent.getProductId());
                    again.setCustomerId(intent.getCustomerId());
                    again.setName(intent.getName());
                    again.setPhone(intent.getPhone());
                    again.setPasscode(newPasscode());
                    again.setSubmittedAt(at);
                    again.setStage(IntentStage.queued);
                    again.setOutcome(null);
                    intents.saveAndFlush(again);
                } else {
                    intent.setOutcome(IntentOutcome.voided);
                    intents.saveAndFlush(intent);
                }
            }
            product.setStatus(ProductStatus.withdrawn);
            product.setTradingIntentId(null);
            // 手动下架不进历史：明确清空交易结果标记
            product.setResult(null);
            product.setResultAt(null);
            products.saveAndFlush(product);

            ObjectNode result = mapper.createObjectNode();
            result.put("ok", true);
            result.put("status", product.getStatus().code());
            result.put("handledIntents", handled);
            return result;
        });
    }

    // ==================== 卖家：售罄补货并重新上架（22269 / 22270）====================

    /** 补货：仅售罄（depleted）商品可用；库存 0 → 正数后状态仍为已下架。 */
    public ObjectNode restock(String session, long productId, Integer stock) {
        return write(() -> {
            auth.requireSession(session);
            ProductEntity product = requireProduct(productId);
            if (product.getStatus() != ProductStatus.depleted) {
                throw conflict("productId", "仅售罄下架的商品可以补货");
            }
            if (stock == null || stock < 1) throw bad("stock", "补货数量必须为大于等于 1 的整数");
            product.setStock(stock);
            products.saveAndFlush(product);
            ObjectNode result = mapper.createObjectNode();
            result.put("ok", true);
            result.put("stock", product.getStock());
            result.put("status", product.getStatus().code());
            return result;
        });
    }

    /**
     * 重新上架：仅售罄（depleted）商品可用；必须先补货（库存 > 0）。
     * 原队列不恢复——旧意向统一记未成交，重新上架后队列为空。
     */
    public ObjectNode relist(String session, long productId) {
        return write(() -> {
            auth.requireSession(session);
            ProductEntity product = requireProduct(productId);
            if (product.getStatus() != ProductStatus.depleted) {
                throw conflict("productId", "仅售罄下架的商品可以重新上架");
            }
            if (product.getStock() == null || product.getStock() <= 0) {
                throw conflict("productId", "请先补货，再将库存大于 0 的商品重新上架");
            }
            String at = now();
            int cleared = 0;
            for (IntentEntity intent : allIntents(product.getId())) {
                if (intent.getStage() == IntentStage.closed) continue;
                cleared++;
                intent.setStage(IntentStage.closed);
                intent.setOutcome(IntentOutcome.unsold);
                intent.setClosedAt(at);
                intents.saveAndFlush(intent);
            }
            product.setStatus(ProductStatus.active);
            product.setTradingIntentId(null);
            product.setResult(null);
            product.setResultAt(null);
            products.saveAndFlush(product);
            ObjectNode result = mapper.createObjectNode();
            result.put("ok", true);
            result.put("status", product.getStatus().code());
            result.put("clearedIntents", cleared);
            return result;
        });
    }

    // ==================== 历史商品 ====================

    /** 历史商品：发生过交易结果（成功/失败）的商品；手动下架与售罄不在其中。 */
    public ArrayNode productHistory(String session) {
        return write(() -> {
            auth.requireSession(session);
            ArrayNode list = mapper.createArrayNode();
            for (ProductEntity product : products.findByResultIsNotNullOrderByResultAtDescIdDesc()) {
                ObjectNode item = mapper.createObjectNode();
                item.put("id", product.getId());
                item.put("name", product.getName());
                item.put("priceCents", product.getPriceCents());
                item.put("stock", product.getStock());
                item.put("status", product.getStatus().code());
                item.put("result", product.getResult());
                putNullable(item, "resultAt", product.getResultAt());
                putNullable(item, "createdAt", product.getCreatedAt());
                ArrayNode records = mapper.createArrayNode();
                for (IntentEntity intent : allIntents(product.getId())) {
                    ObjectNode record = mapper.createObjectNode();
                    record.put("id", intent.getId());
                    record.put("name", intent.getName());
                    record.put("phone", intent.getPhone());
                    record.put("submittedAt", intent.getSubmittedAt());
                    record.put("stage", intent.getStage().code());
                    record.put("outcome", intent.getOutcome() == null ? "pending" : intent.getOutcome().code());
                    putNullable(record, "closedAt", intent.getClosedAt());
                    records.add(record);
                }
                item.set("intents", records);
                list.add(item);
            }
            return list;
        });
    }

    // ==================== 内部实现 ====================

    private ObjectNode workbenchItem(ProductEntity product) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", product.getId());
        item.put("name", product.getName());
        item.put("priceCents", product.getPriceCents());
        item.put("stock", product.getStock() == null ? 0 : product.getStock());
        item.put("status", product.getStatus().code());
        putNullable(item, "frozenAt", product.getFrozenAt());
        List<IntentEntity> all = allIntents(product.getId());
        int queued = 0;
        ArrayNode queue = mapper.createArrayNode();
        for (IntentEntity intent : all) {
            if (intent.getStage() != IntentStage.closed) queued++;
        }
        item.put("queueCount", queued);
        int shown = 0;
        for (IntentEntity intent : all) {
            if (intent.getStage() != IntentStage.queued) continue;
            if (shown++ >= 8) break;
            ObjectNode node = mapper.createObjectNode();
            node.put("id", intent.getId());
            node.put("name", intent.getName());
            node.put("phone", intent.getPhone());
            node.put("submittedAt", intent.getSubmittedAt());
            node.put("position", position(product.getId(), intent.getId()));
            if (intent.getCustomerId() != null) node.put("customerId", intent.getCustomerId());
            queue.add(node);
        }
        item.set("queue", queue);
        IntentEntity trading = tradingIntent(product);
        if (trading == null) {
            item.putNull("trading");
        } else {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", trading.getId());
            node.put("name", trading.getName());
            node.put("phone", trading.getPhone());
            node.put("submittedAt", trading.getSubmittedAt());
            item.set("trading", node);
        }
        return item;
    }

    private int position(Long productId, Long intentId) {
        List<IntentEntity> queued = intents.findByProductIdAndStageOrderBySubmittedAtAscIdAsc(
                productId, IntentStage.queued);
        for (int i = 0; i < queued.size(); i++) {
            if (Objects.equals(queued.get(i).getId(), intentId)) return i + 1;
        }
        return 0;
    }

    private ProductEntity requireProduct(long id) {
        return products.findById(id).orElseThrow(this::missing);
    }

    private IntentEntity tradingIntent(ProductEntity product) {
        if (product == null || product.getTradingIntentId() == null) return null;
        return intents.findById(product.getTradingIntentId()).orElse(null);
    }

    /** 全部意向，按 (提交时间, id) 升序——与队列口径一致。 */
    private List<IntentEntity> allIntents(Long productId) {
        List<IntentEntity> all = new ArrayList<>(intents.findByProductIdOrderBySubmittedAtAscIdAsc(productId));
        all.sort(Comparator.comparing(IntentEntity::getSubmittedAt).thenComparing(IntentEntity::getId));
        return all;
    }

    private String newPasscode() {
        StringBuilder code = new StringBuilder();
        do {
            code.setLength(0);
            for (int i = 0; i < 8; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        } while (intents.existsByPasscode(code.toString()));
        return code.toString();
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private <T> T write(Supplier<T> action) {
        writeLock.lock();
        try {
            return transaction.execute(status -> action.get());
        } finally {
            writeLock.unlock();
        }
    }

    private CatalogException bad(String field, String message) {
        return new CatalogException(HttpStatus.BAD_REQUEST, field, message);
    }

    private CatalogException conflict(String field, String message) {
        return new CatalogException(HttpStatus.CONFLICT, field, message);
    }

    private CatalogException missing() {
        return new CatalogException(HttpStatus.NOT_FOUND, "productId", "商品不存在或已下架");
    }
}
