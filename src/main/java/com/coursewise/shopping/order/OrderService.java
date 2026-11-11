package com.coursewise.shopping.order;

import com.coursewise.shopping.customer.CustomerService;
import com.coursewise.shopping.domain.CartItemEntity;
import com.coursewise.shopping.domain.CustomerEntity;
import com.coursewise.shopping.domain.FavoriteEntity;
import com.coursewise.shopping.domain.OrderEntity;
import com.coursewise.shopping.domain.OrderItemEntity;
import com.coursewise.shopping.domain.OrderStatus;
import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.domain.ProductStatus;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 买家端全闭环业务：收藏 → 购物车 → 提交订单 → 客户取消 → 查看我的订单。
 *
 * <p>对应需求 14201 / 14202 / 14203 / 14204 / 14205（SRS FR-C-06 ~ FR-C-10）。
 *
 * <p><b>一致性设计</b>：SQLite 单实例部署，写操作统一走 {@link TransactionTemplate} 并在一把
 * 公平可重入锁内执行，锁覆盖到事务提交，因此「校验库存 → 扣减 → 建订单 → 删购物车项」
 * 在进程内是串行的；同时在数据库层用条件更新（{@code WHERE stock >= ?}）兜底，
 * 即便绕过本锁也不会把库存扣成负数（BR-03 / BR-09 / 不超卖）。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    /** 单页上限，防止一次请求拉过多订单。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final CartItemRepository cart;
    private final FavoriteRepository favorites;
    private final ProductRepository products;
    private final CustomerService customers;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    /** 覆盖事务提交范围的写锁（下单扣库存、取消回补库存、购物车与收藏的写操作共用）。 */
    private final ReentrantLock writeLock = new ReentrantLock(true);

    public OrderService(OrderRepository orders, OrderItemRepository orderItems, CartItemRepository cart,
                        FavoriteRepository favorites, ProductRepository products, CustomerService customers,
                        ObjectMapper mapper, PlatformTransactionManager manager) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.cart = cart;
        this.favorites = favorites;
        this.products = products;
        this.customers = customers;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
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
                if (products.deductStock(product.getId(), item.getQuantity()) == 0) {
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

    // ==================== 14204 客户取消订单（FR-C-09）====================

    /**
     * 客户取消订单：仅「开始发货」之前可取消（BR-15），立即生效、不填原因、不二次确认（BR-16）。
     *
     * <p>取消动作<b>同时</b>触发库存回补：按明细数量加回，商品若因售罄下架则自动恢复在售
     * （BR-04 / SRS §5.3）。不涉及退款（BR-17）。
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
            for (OrderItemEntity line : orderItems.findByOrderIdOrderByIdAsc(orderId)) {
                products.restoreStock(line.getProductId(), line.getQuantity());
            }
            order.setStatus(OrderStatus.cancelled);
            orders.saveAndFlush(order);
            return mapper.createObjectNode()
                    .put("orderId", orderId)
                    .put("status", order.getStatus().code())
                    .put("statusLabel", order.getStatus().label())
                    .put("cancellable", false);
        });
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

    /** 商品当前是否可以被加购/下单：在售或恢复在售，且可售库存 > 0。 */
    private boolean isPurchasable(ProductEntity product) {
        ProductStatus status = product.getStatus();
        boolean sellable = status == ProductStatus.active || status == ProductStatus.resumed;
        return sellable && product.getStock() != null && product.getStock() > 0;
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
        writeLock.lock();
        try {
            return transaction.execute(status -> action.get());
        } finally {
            writeLock.unlock();
        }
    }

    /** 供测试与内部调用：某订单明细（保持与其他模块一致的访问口径）。 */
    Optional<List<OrderItemEntity>> linesOf(long orderId) {
        return orders.findById(orderId).map(order -> orderItems.findByOrderIdOrderByIdAsc(orderId));
    }
}
