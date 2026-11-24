package com.coursewise.shopping.order;

import com.coursewise.shopping.ShopWriteTransaction;

import com.coursewise.shopping.customer.CustomerService;
import com.coursewise.shopping.domain.CartItemEntity;
import com.coursewise.shopping.domain.CustomerEntity;
import com.coursewise.shopping.domain.FavoriteEntity;
import com.coursewise.shopping.domain.OrderEntity;
import com.coursewise.shopping.domain.OrderItemEntity;
import com.coursewise.shopping.domain.OrderStatus;
import com.coursewise.shopping.domain.PayChannel;
import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.repository.CartItemRepository;
import com.coursewise.shopping.repository.FavoriteRepository;
import com.coursewise.shopping.repository.OrderItemRepository;
import com.coursewise.shopping.repository.OrderRepository;
import com.coursewise.shopping.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 买家端全闭环业务：收藏 → 购物车 → 提交订单 → 客户取消 → 查看我的订单，
 * 以及挑战需求C 新增的<b>支付订单</b>（14257 / FR-C-09）、<b>确认收货</b>（14258 / FR-C-11）、
 * <b>查询物流</b>（14259 / FR-C-12）。
 *
 * <p>对应需求 14201 / 14202 / 14203 / 14204 / 14205（挑战需求B）与 14257 / 14258 / 14259（挑战需求C），
 * SRS FR-C-06 ~ FR-C-12。
 *
 * <p><b>一致性设计</b>：SQLite 单实例部署，写操作统一走 {@link ShopWriteTransaction} 并在一把
 * 公平可重入锁内执行，锁覆盖到事务提交，因此「校验库存 → 扣减 → 建订单 → 删购物车项」
 * 在进程内是串行的；同时在数据库层用条件更新（{@code WHERE stock >= ?}）兜底，
 * 即便绕过本锁也不会把库存扣成负数（BR-03 / BR-09 / 不超卖）。
 *
 * <p>支付、确认收货、超时取消三处同样以条件更新落库（{@code WHERE status = 期望状态}），
 * 重复点击或并发请求只会有一笔生效，其余按幂等返回（BR-55 / BR-57 / AC-14）。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    /** 单页上限，防止一次请求拉过多订单。 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 支付窗口：下单起 24h 未支付则自动取消并回补库存（BR-21 / S-11）。 */
    private static final java.time.Duration PAYMENT_WINDOW = java.time.Duration.ofHours(24);

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final CartItemRepository cart;
    private final FavoriteRepository favorites;
    private final ProductRepository products;
    private final CustomerService customers;
    private final ObjectMapper mapper;
    private final ShopWriteTransaction writes;

    public OrderService(OrderRepository orders, OrderItemRepository orderItems, CartItemRepository cart,
                        FavoriteRepository favorites, ProductRepository products, CustomerService customers,
                        ObjectMapper mapper, ShopWriteTransaction writes) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.cart = cart;
        this.favorites = favorites;
        this.products = products;
        this.customers = customers;
        this.mapper = mapper;
        this.writes = writes;
    }

    // ==================== 14201 收藏商品（FR-C-06）====================

    /** 我的收藏：商品仍保留展示，已下架条目带标记、可点开只读详情、无加购/下单入口。 */
    @Transactional(readOnly = true)
    public ObjectNode listFavorites(String session) {
        CustomerEntity customer = customers.requireCustomer(session);
        ArrayNode items = mapper.createArrayNode();
        for (FavoriteEntity favorite : favorites.findByCustomerIdOrderByIdDesc(customer.getId())) {
            ProductEntity product = products.findById(favorite.getProductId()).orElse(null);
            // 商品记录缺失（历史脏数据）时不展示，避免页面出现空壳条目；
            // 正常下架的商品仍然展示并标注「已下架」。
            if (product == null) continue;
            boolean available = isPurchasable(product);
            ObjectNode node = mapper.createObjectNode();
            node.put("favoriteId", favorite.getId());
            node.put("productId", product.getId());
            node.put("name", product.getName());
            node.put("priceCents", product.getPriceCents());
            node.put("image", imageOf(product));
            node.put("favoritedAt", favorite.getCreatedAt());
            node.put("available", available);
            node.put("status", available ? "active" : "offline");
            node.put("statusLabel", available ? "在售" : "已下架");
            items.add(node);
        }
        return mapper.createObjectNode().set("items", items);
    }

    /** 收藏状态（详情页渲染按钮用）。 */
    @Transactional(readOnly = true)
    public ObjectNode favoriteState(String session, long productId) {
        CustomerEntity customer = customers.requireCustomer(session);
        return mapper.createObjectNode()
                .put("productId", productId)
                .put("favorited", favorites.existsByCustomerIdAndProductId(customer.getId(), productId));
    }

    /**
     * 收藏商品（幂等）：重复收藏不产生第二条记录（FR-C-06 验收 3），返回当前条目。
     *
     * <p>已下架商品同样允许被收藏——收藏列表对已下架商品是例外入口（BR-50 / BR-33）。
     */
    public ObjectNode addFavorite(String session, Long productId) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            if (productId == null) throw bad("productId", "请指定要收藏的商品");
            ProductEntity product = products.findById(productId)
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "productId", "商品不存在"));
            FavoriteEntity favorite = favorites.findByCustomerIdAndProductId(customer.getId(), productId)
                    .orElseGet(() -> {
                        FavoriteEntity created = new FavoriteEntity();
                        created.setCustomerId(customer.getId());
                        created.setProductId(productId);
                        created.setCreatedAt(now());
                        return favorites.saveAndFlush(created);
                    });
            return mapper.createObjectNode()
                    .put("favoriteId", favorite.getId())
                    .put("productId", product.getId())
                    .put("favorited", true);
        });
    }

    /** 取消收藏；未收藏时同样返回成功（幂等），便于前端重复点击。 */
    public ObjectNode removeFavorite(String session, long productId) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            favorites.findByCustomerIdAndProductId(customer.getId(), productId)
                    .ifPresent(favorites::delete);
            return mapper.createObjectNode().put("productId", productId).put("favorited", false);
        });
    }

    // ==================== 14202 购物车管理（FR-C-07）====================

    /**
     * 我的购物车：一条商品一行，返回可售库存以便前端提示；已下架商品保留并标记
     * {@code available=false}（前端置灰、禁止勾选下单）。
     */
    @Transactional(readOnly = true)
    public ObjectNode listCart(String session) {
        CustomerEntity customer = customers.requireCustomer(session);
        ArrayNode items = mapper.createArrayNode();
        for (CartItemEntity item : cart.findByCustomerIdOrderByIdAsc(customer.getId())) {
            ProductEntity product = products.findById(item.getProductId()).orElse(null);
            if (product == null) continue;
            boolean available = isPurchasable(product);
            ObjectNode node = mapper.createObjectNode();
            node.put("id", item.getId());
            node.put("productId", product.getId());
            node.put("name", product.getName());
            node.put("priceCents", product.getPriceCents());
            node.put("image", imageOf(product));
            node.put("quantity", item.getQuantity());
            node.put("stock", product.getStock() == null ? 0 : product.getStock());
            node.put("subtotalCents", product.getPriceCents() * item.getQuantity());
            node.put("available", available);
            node.put("status", available ? "active" : "offline");
            node.put("statusLabel", available ? "在售" : "已下架");
            items.add(node);
        }
        return mapper.createObjectNode().set("items", items);
    }

    /**
     * 加入购物车：同一客户 + 同一商品只有一行，重复加购累加数量（FR-C-07 规则 2）。
     * <b>不占用、不锁定、也不校验库存</b>（BR-02：加购后商品可售库存数字不变）。
     */
    public ObjectNode addToCart(String session, Long productId, Integer quantity) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            if (productId == null) throw bad("productId", "请指定要加入购物车的商品");
            int add = quantity == null ? 1 : quantity;
            if (add < 1) throw bad("quantity", "数量必须为大于等于 1 的整数");
            ProductEntity product = products.findById(productId)
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "productId", "商品不存在"));
            // 已下架商品不能加购，但已在购物车中的条目不会被自动删除（BR-49）。
            if (!isPurchasable(product)) {
                throw new OrderException(HttpStatus.CONFLICT, "productId", "商品已下架，暂不能加入购物车");
            }
            CartItemEntity item = cart.findByCustomerIdAndProductId(customer.getId(), productId).orElse(null);
            if (item == null) {
                item = new CartItemEntity();
                item.setCustomerId(customer.getId());
                item.setProductId(productId);
                item.setQuantity(add);
            } else {
                item.setQuantity(item.getQuantity() + add);
            }
            cart.saveAndFlush(item);
            return mapper.createObjectNode()
                    .put("id", item.getId())
                    .put("productId", productId)
                    .put("quantity", item.getQuantity());
        });
    }

    /** 修改数量：数量 ≥ 1；只允许改本人的购物车项。 */
    public ObjectNode updateCartQuantity(String session, long itemId, Integer quantity) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            if (quantity == null || quantity < 1) throw bad("quantity", "数量必须为大于等于 1 的整数");
            CartItemEntity item = cart.findByIdAndCustomerId(itemId, customer.getId())
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "id", "购物车项不存在"));
            item.setQuantity(quantity);
            cart.saveAndFlush(item);
            return mapper.createObjectNode().put("id", item.getId()).put("quantity", item.getQuantity());
        });
    }

    /** 移除商品：只允许移除本人的购物车项。 */
    public ObjectNode removeCartItem(String session, long itemId) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            CartItemEntity item = cart.findByIdAndCustomerId(itemId, customer.getId())
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "id", "购物车项不存在"));
            cart.delete(item);
            return mapper.createObjectNode().put("ok", true).put("id", itemId);
        });
    }

    // ==================== 14203 提交订单（FR-C-08）====================

    /**
     * 提交订单：唯一入口是购物车，勾选一件或多件商品生成<b>一笔</b>订单。
     *
     * <p>处理流程（同一事务 + 写锁）：
     * <ol>
     *   <li>只接受本人购物车中、被勾选的条目；未勾选的不受影响（BR-25 只删选中项）；</li>
     *   <li><b>逐商品</b>校验：已下架或库存不足的商品被剔除并记录原因，其余照常下单（BR-06）；</li>
     *   <li>对被接受的商品逐条执行条件扣减（{@code WHERE stock >= qty}），任何一条扣减失败都不生成订单；
     *       扣减在同一事务内完成，失败即整体回滚（BR-03 原子性）；</li>
     *   <li>写入订单与明细快照（名称、单价），总金额由服务端 Σ(单价 × 数量) 计算（BR-24）；</li>
     *   <li>删除本次成功下单的购物车项。</li>
     * </ol>
     *
     * <p>若<b>全部</b>勾选项都不可下单，则不生成订单，返回 409 并带逐商品缺货明细。
     */
    public ObjectNode submit(String session, List<Long> cartItemIds) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            List<Long> ids = cartItemIds == null ? List.of()
                    : new ArrayList<>(new LinkedHashSet<>(cartItemIds.stream().filter(java.util.Objects::nonNull).toList()));
            if (ids.isEmpty()) throw bad("cartItemIds", "请先勾选要下单的商品");

            List<CartItemEntity> mine = cart.findByCustomerIdAndIdIn(customer.getId(), ids);
            if (mine.isEmpty()) throw bad("cartItemIds", "勾选的商品已不在购物车中，请刷新后重试");

            Map<String, String> shortages = new LinkedHashMap<>();
            List<CartItemEntity> accepted = new ArrayList<>();
            long totalCents = 0;

            for (CartItemEntity item : mine) {
                ProductEntity product = products.findById(item.getProductId()).orElse(null);
                if (product == null) {
                    shortages.put(String.valueOf(item.getProductId()), "商品不存在或已删除，不能下单");
                    continue;
                }
                if (!isPurchasable(product)) {
                    shortages.put(String.valueOf(product.getId()), "商品已下架，不能下单");
                    // 已下架商品从购物车中移除勾选资格，但不自动删除条目（BR-49）
                    continue;
                }
                if (product.getStock() == null || product.getStock() < item.getQuantity()) {
                    shortages.put(String.valueOf(product.getId()),
                            "库存不足（可售 " + (product.getStock() == null ? 0 : product.getStock())
                                    + " 件，需要 " + item.getQuantity() + " 件），不能下单");
                    continue;
                }
                // 条件扣减：受影响行数 0 说明并发下库存已被抢走，同样按缺货处理
                if (products.deductStock(product.getId(), item.getQuantity(), Instant.now().toString()) == 0) {
                    shortages.put(String.valueOf(product.getId()), "库存不足，不能下单");
                    continue;
                }
                accepted.add(item);
                totalCents += product.getPriceCents() * item.getQuantity();
            }

            if (accepted.isEmpty()) {
                throw OrderException.items(HttpStatus.CONFLICT,
                        "所选商品均无法下单：" + String.join("；", shortages.values()), shortages);
            }

            OrderEntity order = new OrderEntity();
            order.setCustomerId(customer.getId());
            order.setTotalCents(totalCents);
            order.setStatus(OrderStatus.placed);
            order.setCreatedAt(now());
            orders.saveAndFlush(order);

            ArrayNode lines = mapper.createArrayNode();
            for (CartItemEntity item : accepted) {
                ProductEntity product = products.findById(item.getProductId()).orElseThrow();
                OrderItemEntity line = new OrderItemEntity();
                line.setOrderId(order.getId());
                line.setProductId(product.getId());
                // 快照：商品名称与单价在下单时固化，之后卖家改价不再影响本单（BR-20 / AC-08）
                line.setProductName(product.getName());
                line.setUnitPriceCents(product.getPriceCents());
                line.setQuantity(item.getQuantity());
                orderItems.saveAndFlush(line);
                lines.add(itemView(line));
            }

            // 仅删除成功下单的购物车项，未勾选与缺货项保留（BR-25）
            cart.deleteByCustomerIdAndIdIn(customer.getId(), accepted.stream().map(CartItemEntity::getId).toList());

            ObjectNode result = orderView(order, lines);
            ObjectNode rejected = mapper.createObjectNode();
            shortages.forEach(rejected::put);
            result.set("rejectedItems", rejected);
            result.put("message", shortages.isEmpty()
                    ? "下单成功"
                    : "部分商品缺货未能下单：" + String.join("；", shortages.values()));
            return result;
        });
    }

    /** 商品详情页的「加入购物车」「收藏」状态一次性返回，减少前端往返。 */
    @Transactional(readOnly = true)
    public ObjectNode productActions(String session, long productId) {
        CustomerEntity customer = customers.requireCustomer(session);
        return mapper.createObjectNode()
                .put("productId", productId)
                .put("favorited", favorites.existsByCustomerIdAndProductId(customer.getId(), productId))
                .put("inCart", cart.findByCustomerIdAndProductId(customer.getId(), productId).isPresent());
    }

    // ==================== 14204 客户取消订单（FR-C-10）====================

    /**
     * 客户取消订单：仅「开始发货」之前可取消（BR-15），立即生效、不填原因、不二次确认（BR-16）。
     *
     * <p>取消动作<b>同时</b>触发库存回补：按明细数量加回，商品若因售罄下架则自动恢复在售
     * （BR-04 / SRS §5.3）。
     *
     * <p><b>退款分段</b>（BR-17 / K-15）：
     * <ul>
     *   <li>「客户下单 / 待支付」段取消：未付款，<b>无退款</b>；</li>
     *   <li>「已支付 / 商家确认 / 备货完成」段取消：按订单记录的支付渠道<b>原路退款</b>，
     *       退款金额 = 订单总金额（明细快照合计，BR-53 / BR-73）。</li>
     * </ul>
     *
     * <p>越权保护：按「本人 + 订单号」定位订单，他人订单一律视为不存在（AC-10）。
     */
    public ObjectNode cancel(String session, long orderId) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            OrderEntity order = orders.findByIdAndCustomerId(orderId, customer.getId())
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在或无权访问"));
            if (order.getStatus() == OrderStatus.cancelled) {
                throw new OrderException(HttpStatus.CONFLICT, "orderId", "订单已取消");
            }
            if (!order.getStatus().isCancellableByCustomer()) {
                throw new OrderException(HttpStatus.CONFLICT, "orderId",
                        "订单已「" + order.getStatus().label() + "」，不能再取消");
            }
            // 已付款段取消才产生原路退款，渠道取订单上记录的支付渠道
            boolean refunded = order.getStatus().isPaidStage() && order.getStatus() != OrderStatus.completed;
            PayChannel channel = refunded ? order.getPayChannel() : null;
            long refundCents = refunded ? order.getTotalCents() : 0;

            for (OrderItemEntity line : orderItems.findByOrderIdOrderByIdAsc(orderId)) {
                products.restoreStock(line.getProductId(), line.getQuantity());
            }
            order.setStatus(OrderStatus.cancelled);
            orders.saveAndFlush(order);

            ObjectNode result = mapper.createObjectNode()
                    .put("orderId", orderId)
                    .put("status", order.getStatus().code())
                    .put("statusLabel", order.getStatus().label())
                    .put("cancellable", false)
                    .put("refunded", refunded)
                    .put("refundCents", refundCents);
            if (refunded && channel != null) {
                result.put("refundChannel", channel.code());
                result.put("refundChannelLabel", channel.label());
                result.put("message", "订单已取消，" + money(refundCents) + " 已原路退回" + channel.label());
            } else {
                result.putNull("refundChannel");
                result.putNull("refundChannelLabel");
                result.put("message", "订单已取消，未付款无需退款");
            }
            return result;
        });
    }

    // ==================== 14257 支付订单（FR-C-09）====================

    /**
     * 发起支付（API-19）：客户选定四种渠道之一，订单进入「待支付」（FR-C-09 规则 1）。
     *
     * <p>前后端分工：本接口只负责「进入待支付 + 记录客户选定的渠道」，
     * 真正的支付动作由 {@link #pay} 完成，模拟支付页因此可以呈现「选渠道 → 支付成功 / 失败」
     * 两段式交互，而不需要在服务端伪造支付成功。
     *
     * <p>允许状态：「客户下单」（首次发起）与「待支付」（换渠道或失败后重试，规则 4）。
     * 「已支付」及之后一律无支付入口（验收 6）。
     */
    public ObjectNode startPayment(String session, long orderId, String channelCode, Boolean succeed) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            OrderEntity order = orders.findByIdAndCustomerId(orderId, customer.getId())
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在或无权访问"));
            PayChannel channel = parseChannel(channelCode);
            requirePayable(order);

            orders.markPendingPayment(orderId, channel);
            OrderEntity fresh = orders.findById(orderId).orElseThrow();

            ObjectNode result = mapper.createObjectNode()
                    .put("orderId", orderId)
                    .put("orderNo", orderNo(orderId))
                    .put("status", fresh.getStatus().code())
                    .put("statusLabel", fresh.getStatus().label())
                    .put("payChannel", channel.code())
                    .put("payChannelLabel", channel.label())
                    .put("amountCents", fresh.getTotalCents())
                    .put("amount", money(fresh.getTotalCents()))
                    .put("paymentPage", "/payment.html?orderId=" + orderId);
            result.put("message", "已进入模拟支付页，请选择支付渠道完成支付");
            return result;
        });
    }

    /**
     * 模拟支付（API-19）：不接真实网关（EX-02 / BR-54），由 {@code succeed} 模拟支付结果。
     *
     * <p><b>支付成功</b>：订单转「已支付」并记录渠道，清空待支付起点（规则 3）。
     * <p><b>支付失败</b>：订单<b>停留「待支付」</b>，不设独立失败状态，可重新发起（规则 4）；
     * 失败不影响库存，也不产生任何订单变更。
     * <p><b>幂等</b>：同一订单重复发起支付，只有第一次条件更新生效；已为「已支付」时
     * 直接返回当前状态并标记 {@code idempotent=true}，<b>不产生第二笔扣款记录</b>（BR-55 / AC-14）。
     *
     * <p><b>24h 超时兜底</b>：下单起算（S-11）满 24h 仍未支付，支付入口关闭并提示已自动取消，
     * 避免「超时扫描任务尚未执行时客户仍能付款」造成资损（BR-57）。
     */
    public ObjectNode pay(String session, long orderId, String channelCode, boolean succeed) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            OrderEntity order = orders.findByIdAndCustomerId(orderId, customer.getId())
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在或无权访问"));

            // 幂等：已支付（或已推进）重复提交不再扣款，直接回当前状态
            if (order.getStatus().isPaidStage() && order.getStatus() != OrderStatus.cancelled) {
                ObjectNode result = paymentView(orderId, order.getStatus(), order.getPayChannel(), order.getTotalCents());
                result.put("idempotent", true);
                result.put("message", "订单已支付，无需重复支付");
                return result;
            }
            requirePayable(order);

            PayChannel channel = parseChannel(channelCode != null ? channelCode
                    : order.getPayChannel() == null ? null : order.getPayChannel().code());

            if (!succeed) {
                // 支付失败：停留待支付，记录本次选定的渠道以便重试（不设失败状态）
                orders.markPendingPayment(orderId, channel);
                OrderEntity fresh = orders.findById(orderId).orElseThrow();
                ObjectNode result = paymentView(orderId, fresh.getStatus(), channel, fresh.getTotalCents());
                result.put("paid", false);
                result.put("idempotent", false);
                result.put("message", "支付失败，订单仍为「待支付」，可重新发起支付");
                return result;
            }

            if (orders.markPaid(orderId, channel) == 0) {
                // 并发下被别的请求抢先支付：按幂等返回当前真实状态，绝不二次扣款
                OrderEntity fresh = orders.findById(orderId).orElseThrow();
                ObjectNode result = paymentView(orderId, fresh.getStatus(), fresh.getPayChannel(), fresh.getTotalCents());
                result.put("idempotent", true);
                result.put("message", "订单已支付，无需重复支付");
                return result;
            }

            OrderEntity fresh = orders.findById(orderId).orElseThrow();
            ObjectNode result = paymentView(orderId, fresh.getStatus(), fresh.getPayChannel(), fresh.getTotalCents());
            result.put("paid", true);
            result.put("idempotent", false);
            result.put("message", "支付成功，" + channel.label() + " 已扣款 " + money(fresh.getTotalCents()));
            return result;
        });
    }

    /**
     * 支付能力视图：订单页渲染支付入口时使用，避免前端自己推算状态机。
     *
     * <p>{@code payable} 为真仅当状态是「客户下单 / 待支付」且未超 24h；已超时的订单
     * 会被超时机制取消，因此这里直接返回不可支付并说明原因。
     */
    @Transactional(readOnly = true)
    public ObjectNode paymentOptions(String session, long orderId) {
        CustomerEntity customer = customers.requireCustomer(session);
        OrderEntity order = orders.findByIdAndCustomerId(orderId, customer.getId())
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在或无权访问"));

        boolean expired = order.getStatus().isAwaitingPayment() && isExpired(order);
        boolean payable = order.getStatus().isPayableByCustomer() && !expired;

        ObjectNode result = mapper.createObjectNode();
        result.put("orderId", orderId);
        result.put("orderNo", orderNo(orderId));
        result.put("status", order.getStatus().code());
        result.put("statusLabel", order.getStatus().label());
        result.put("payable", payable);
        result.put("expired", expired);
        result.put("amountCents", order.getTotalCents());
        result.put("amount", money(order.getTotalCents()));
        result.put("deadline", deadlineOf(order.getCreatedAt()));
        result.put("remainingSeconds", remainingSeconds(order));
        if (order.getPayChannel() != null) {
            result.put("payChannel", order.getPayChannel().code());
            result.put("payChannelLabel", order.getPayChannel().label());
        } else {
            result.putNull("payChannel");
            result.putNull("payChannelLabel");
        }
        ArrayNode channels = mapper.createArrayNode();
        for (PayChannel channel : PayChannel.values()) {
            channels.add(mapper.createObjectNode().put("code", channel.code()).put("label", channel.label()));
        }
        result.set("channels", channels);
        result.put("paymentPage", "/payment.html?orderId=" + orderId);
        return result;
    }

    // ==================== 14258 确认收货（FR-C-11）====================

    /**
     * 客户确认收货（API-20）：「开始发货」→「交易完成」，终态不可逆（BR-13）。
     *
     * <p>确认收货是<b>动作</b>而非状态；没有物流签收自动触发、没有超时自动确认（EX-09），
     * 只能由客户在订单页点击。库存不再变动（BR-05：预占在此落账）。
     *
     * <p>重复点击或对已交易完成的订单再次确认，按幂等返回当前状态并标记
     * {@code idempotent=true}，不报错也不重复流转。
     */
    public ObjectNode confirmReceipt(String session, long orderId) {
        return write(() -> {
            CustomerEntity customer = customers.requireCustomer(session);
            OrderEntity order = orders.findByIdAndCustomerId(orderId, customer.getId())
                    .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在或无权访问"));

            if (order.getStatus() == OrderStatus.completed) {
                ObjectNode result = confirmView(orderId, OrderStatus.completed, order.getTrackingNo());
                result.put("idempotent", true);
                result.put("message", "订单已「交易完成」，无需重复确认");
                return result;
            }
            if (!order.getStatus().isConfirmableByCustomer()) {
                throw new OrderException(HttpStatus.CONFLICT, "orderId",
                        "订单当前为「" + order.getStatus().label() + "」，只有「开始发货」的订单可以确认收货");
            }
            if (orders.markCompleted(orderId) == 0) {
                OrderEntity fresh = orders.findById(orderId).orElseThrow();
                if (fresh.getStatus() == OrderStatus.completed) {
                    ObjectNode result = confirmView(orderId, OrderStatus.completed, fresh.getTrackingNo());
                    result.put("idempotent", true);
                    result.put("message", "订单已「交易完成」，无需重复确认");
                    return result;
                }
                throw new OrderException(HttpStatus.CONFLICT, "orderId", "订单状态已被其他操作变更，请刷新后重试");
            }
            OrderEntity fresh = orders.findById(orderId).orElseThrow();
            ObjectNode result = confirmView(orderId, fresh.getStatus(), fresh.getTrackingNo());
            result.put("idempotent", false);
            result.put("message", "确认收货成功，订单已「交易完成」");
            return result;
        });
    }

    // ==================== 14259 查询物流（FR-C-12）====================

    /**
     * 查询物流（API-21）：返回物流单号、物流公司、跟踪信息（BR-60）。
     *
     * <p>只有发货后（「开始发货」「交易完成」，以及发货后被取消的订单）才有物流信息；
     * 未发货订单一律返回 409 并以 {@code issued=false} 说明「尚未发货」，不泄露任何单号。
     *
     * <p>跟踪信息为<b>模拟数据</b>，枚举固定「已发货 / 运输中 / 已签收」（S-16），
     * 由 {@link LogisticsSimulator} 按距发货时间的时长推算，反复查询只会前进不会倒退。
     *
     * <p>越权保护同其他客户侧接口：只接受本人的订单号（AC-10 / 验收 3）。
     */
    @Transactional(readOnly = true)
    public ObjectNode logistics(String session, long orderId) {
        CustomerEntity customer = customers.requireCustomer(session);
        OrderEntity order = orders.findByIdAndCustomerId(orderId, customer.getId())
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在或无权访问"));
        return logisticsView(order);
    }

    /** 订单是否已发货并被取消（单据取消但物流信息仍可查）。 */
    private ObjectNode logisticsView(OrderEntity order) {
        ObjectNode result = mapper.createObjectNode();
        result.put("orderId", order.getId());
        result.put("orderNo", orderNo(order.getId()));
        result.put("status", order.getStatus().code());
        result.put("statusLabel", order.getStatus().label());

        boolean shipped = order.getTrackingNo() != null && !order.getTrackingNo().isBlank();
        result.put("issued", shipped);
        if (!shipped) {
            result.put("message", "订单尚未发货，暂无物流信息");
            result.putNull("trackingNo");
            result.putNull("shipCompany");
            result.putNull("shipCompanyLabel");
            result.putNull("currentStage");
            result.set("traces", mapper.createArrayNode());
            return result;
        }

        Instant now = Instant.now();
        String companyLabel = order.getShipCompany() == null
                ? "快递" : order.getShipCompany().label();
        String city = LogisticsSimulator.cityOf(addressOf(order));
        result.put("trackingNo", order.getTrackingNo());
        result.put("shipCompany", order.getShipCompany() == null ? null : order.getShipCompany().code());
        result.put("shipCompanyLabel", companyLabel);
        result.put("shippedAt", order.getShippedAt());
        result.put("currentStage", LogisticsSimulator.currentStage(order.getShippedAt(), now));
        ArrayNode traces = mapper.createArrayNode();
        for (LogisticsSimulator.Trace trace : LogisticsSimulator.traces(order.getShippedAt(), now, companyLabel, city)) {
            traces.add(mapper.createObjectNode()
                    .put("stage", trace.stage())
                    .put("time", trace.time())
                    .put("description", trace.description()));
        }
        result.set("traces", traces);
        result.put("message", "物流跟踪信息为模拟数据，仅供参考");
        return result;
    }

    // ==================== 14205 查看我的订单（FR-C-10）====================

    /**
     * 我的订单：本人全部订单（进行中 / 交易完成 / 已取消），按下单时间倒序、分页，
     * 明细随订单一起返回，前端在行内展开。只读，不提供修改与删除入口。
     */
    @Transactional(readOnly = true)
    public ObjectNode listMine(String session, int page, int size) {
        CustomerEntity customer = customers.requireCustomer(session);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<OrderEntity> found = orders.findByCustomerIdOrderByCreatedAtDescIdDesc(
                customer.getId(), PageRequest.of(safePage - 1, safeSize));

        List<Long> orderIds = found.getContent().stream().map(OrderEntity::getId).toList();
        Map<Long, List<OrderItemEntity>> grouped = new LinkedHashMap<>();
        if (!orderIds.isEmpty()) {
            for (OrderItemEntity line : orderItems.findByOrderIdInOrderByIdAsc(orderIds)) {
                grouped.computeIfAbsent(line.getOrderId(), key -> new ArrayList<>()).add(line);
            }
        }

        ArrayNode items = mapper.createArrayNode();
        for (OrderEntity order : found.getContent()) {
            ArrayNode lines = mapper.createArrayNode();
            for (OrderItemEntity line : grouped.getOrDefault(order.getId(), List.of())) lines.add(itemView(line));
            items.add(orderView(order, lines));
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", found.getTotalElements());
        result.put("totalPages", Math.max(1, found.getTotalPages()));
        return result;
    }

    /** 单笔订单详情（只返回本人订单；他人订单号 → 404，对应 AC-10）。 */
    @Transactional(readOnly = true)
    public ObjectNode detail(String session, long orderId) {
        CustomerEntity customer = customers.requireCustomer(session);
        OrderEntity order = orders.findByIdAndCustomerId(orderId, customer.getId())
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在或无权访问"));
        ArrayNode lines = mapper.createArrayNode();
        for (OrderItemEntity line : orderItems.findByOrderIdOrderByIdAsc(orderId)) lines.add(itemView(line));
        return orderView(order, lines);
    }

    // ==================== 内部实现 ====================

    /** 订单视图：含状态中文名与「是否可由客户取消」，前端据此决定是否显示取消按钮。 */
    private ObjectNode orderView(OrderEntity order, ArrayNode lines) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", order.getId());
        node.put("orderNo", orderNo(order.getId()));
        node.put("totalCents", order.getTotalCents());
        node.put("status", order.getStatus().code());
        node.put("statusLabel", order.getStatus().label());
        node.put("createdAt", order.getCreatedAt());
        node.put("cancellable", order.getStatus().isCancellableByCustomer());
        node.set("items", lines);

        // ---- 以下为挑战需求C 新增的订单动作与支付 / 物流摘要（FR-C-09 / FR-C-11 / FR-C-12 / FR-C-16）----
        boolean expired = order.getStatus().isAwaitingPayment() && isExpired(order);
        node.put("payable", order.getStatus().isPayableByCustomer() && !expired);
        node.put("expired", expired);
        node.put("confirmable", order.getStatus().isConfirmableByCustomer());
        node.put("logisticsAvailable", order.getStatus().hasLogistics()
                && order.getTrackingNo() != null && !order.getTrackingNo().isBlank());
        node.put("deadline", deadlineOf(order.getCreatedAt()));
        node.put("remainingSeconds", remainingSeconds(order));

        if (order.getPayChannel() != null) {
            node.put("payChannel", order.getPayChannel().code());
            node.put("payChannelLabel", order.getPayChannel().label());
        } else {
            node.putNull("payChannel");
            node.putNull("payChannelLabel");
        }
        putNullable(node, "trackingNo", order.getTrackingNo());
        putNullable(node, "shippedAt", order.getShippedAt());
        if (order.getShipCompany() == null) {
            node.putNull("shipCompany");
            node.putNull("shipCompanyLabel");
        } else {
            node.put("shipCompany", order.getShipCompany().code());
            node.put("shipCompanyLabel", order.getShipCompany().label());
        }
        return node;
    }

    private ObjectNode itemView(OrderItemEntity line) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", line.getId());
        node.put("productId", line.getProductId());
        node.put("productName", line.getProductName());
        node.put("unitPriceCents", line.getUnitPriceCents());
        node.put("quantity", line.getQuantity());
        node.put("subtotalCents", line.subtotalCents());
        return node;
    }

    /** 订单号展示：自增主键左补零（SRS §10.1 S-03：订单号格式为自增）。 */
    private String orderNo(Long id) {
        return id == null ? "" : String.format("%08d", id);
    }

    // ---------- 支付 / 确认收货 / 物流的内部支撑 ----------

    /**
     * 支付前置校验（FR-C-09）：状态必须是「客户下单 / 待支付」，且未超 24h。
     *
     * <p>超时判断是<b>兜底</b>而不是替代超时扫描：扫描任务负责真正取消并回补库存，
     * 这里只负责在扫描尚未执行时关闭支付入口，防止超时订单被付款后又被自动取消。
     */
    private void requirePayable(OrderEntity order) {
        OrderStatus status = order.getStatus();
        if (status == OrderStatus.cancelled) {
            throw new OrderException(HttpStatus.CONFLICT, "orderId", "订单已取消，无法支付");
        }
        if (!status.isPayableByCustomer()) {
            throw new OrderException(HttpStatus.CONFLICT, "orderId",
                    "订单当前为「" + status.label() + "」，无支付入口");
        }
        if (isExpired(order)) {
            throw new OrderException(HttpStatus.CONFLICT, "orderId",
                    "订单已超过支付时限（下单后 24 小时内未支付），系统将自动取消该订单");
        }
    }

    /** 支付结果视图：状态、渠道、金额与幂等标记。 */
    private ObjectNode paymentView(long orderId, OrderStatus status, PayChannel channel, long amountCents) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        node.put("orderNo", orderNo(orderId));
        node.put("status", status.code());
        node.put("statusLabel", status.label());
        if (channel == null) {
            node.putNull("payChannel");
            node.putNull("payChannelLabel");
        } else {
            node.put("payChannel", channel.code());
            node.put("payChannelLabel", channel.label());
        }
        node.put("amountCents", amountCents);
        node.put("amount", money(amountCents));
        node.put("payable", status.isPayableByCustomer());
        return node;
    }

    /** 确认收货结果视图：状态与物流单号（客户看完物流再确认收货后不再跳转）。 */
    private ObjectNode confirmView(long orderId, OrderStatus status, String trackingNo) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        node.put("orderNo", orderNo(orderId));
        node.put("status", status.code());
        node.put("statusLabel", status.label());
        node.put("confirmable", status.isConfirmableByCustomer());
        putNullable(node, "trackingNo", trackingNo);
        return node;
    }

    /** 支付渠道解析：允许 code（如 {@code alipay}）或中文名（如 {@code 支付宝}）。 */
    private PayChannel parseChannel(String value) {
        if (value == null || value.isBlank()) {
            throw bad("payChannel", "请选择支付渠道（银行卡 / 信用卡 / 支付宝 / 微信支付）");
        }
        String text = value.trim();
        for (PayChannel channel : PayChannel.values()) {
            if (channel.code().equalsIgnoreCase(text) || channel.label().equals(text)) return channel;
        }
        throw bad("payChannel", "不支持的支付渠道：" + text);
    }

    /** 下单时间 + 24h 的截止时刻（S-11：起算点=下单时间）。 */
    private String deadlineOf(String createdAt) {
        Instant created = parseInstant(createdAt);
        return created == null ? null : ISO.format(created.plus(PAYMENT_WINDOW));
    }

    /** 距离支付截止还剩多少秒；已超时返回 0。 */
    private long remainingSeconds(OrderEntity order) {
        Instant created = parseInstant(order.getCreatedAt());
        if (created == null || !order.getStatus().isAwaitingPayment()) return 0;
        long seconds = ChronoUnit.SECONDS.between(Instant.now(), created.plus(PAYMENT_WINDOW));
        return Math.max(0, seconds);
    }

    /** 未支付订单是否已超过 24h 支付窗口。 */
    private boolean isExpired(OrderEntity order) {
        Instant created = parseInstant(order.getCreatedAt());
        if (created == null) return false;
        return Instant.now().isAfter(created.plus(PAYMENT_WINDOW));
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 订单收货地址快照。
     *
     * <p>订单表目前不含地址字段：收货地址簿由开发 C 在本次迭代实现，其落地后会以
     * {@code addressSnapshot} 写入订单。为不改动对方的实体与表结构，这里用反射按字段名
     * 读取，字段存在即使用、不存在则回退为空——物流模拟轨迹因此不会因为地址簿的
     * 上线下线而报错，也不占用对方的数据所有权。
     */
    private String addressOf(OrderEntity order) {
        try {
            var field = order.getClass().getDeclaredField("addressSnapshot");
            field.setAccessible(true);
            Object value = field.get(order);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    /** 金额展示：分 → 元，固定两位小数（提示文案用）。 */
    private String money(long cents) {
        return "¥" + String.format("%.2f", cents / 100.0);
    }

    /** 商品当前是否可以被加购/下单：在售或恢复在售，且可售库存 > 0。 */
    private boolean isPurchasable(ProductEntity product) {
        return product.isBuyable() && product.getStock() != null && product.getStock() > 0;
    }

    private String imageOf(ProductEntity product) {
        String image = product.getImage();
        return image == null || image.isBlank() ? "/placeholder.svg" : image;
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    private OrderException bad(String field, String message) {
        return new OrderException(HttpStatus.BAD_REQUEST, field, message);
    }

    private <T> T write(Supplier<T> action) {
        return writes.execute(action);
    }

    /** 供测试与内部调用：某订单明细（保持与其他模块一致的访问口径）。 */
    Optional<List<OrderItemEntity>> linesOf(long orderId) {
        return orders.findById(orderId).map(order -> orderItems.findByOrderIdOrderByIdAsc(orderId));
    }
}
