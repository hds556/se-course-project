package com.coursewise.shopping.seller;

import com.coursewise.shopping.ShoppingService;
import com.coursewise.shopping.domain.CustomerEntity;
import com.coursewise.shopping.domain.OrderEntity;
import com.coursewise.shopping.domain.OrderItemEntity;
import com.coursewise.shopping.domain.OrderStatus;
import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.domain.ProductStatus;
import com.coursewise.shopping.order.OrderException;
import com.coursewise.shopping.repository.CustomerRepository;
import com.coursewise.shopping.repository.OrderItemRepository;
import com.coursewise.shopping.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 卖家端订单处理服务：推进订单状态（14207 / FR-S-08）、卖家取消订单（14208 / FR-S-09）、
 * 查看商品订单记录（14209 / FR-S-12）、查看客户购买历史（14214 / FR-S-11）。
 *
 * <p><b>数据边界</b>：本类只写 {@code order.status} 一个字段。订单表、明细表、购物车表、收藏表
 * 均由开发 A（范君豪）建表与拥有，本类只读它们；商品库存的回补一律调用既有的
 * {@link ProductRepository#restoreStock}，不新增库存写入口（库存写入口归开发 D）。
 *
 * <p><b>状态推进规则</b>（SRS §5.4 / BR-11）：五状态严格顺序推进，
 * 一次只能进一格，不能跳过、不能回退；「交易完成」与「已取消」都是终态，
 * 「交易完成」不可撤销。
 *
 * <p><b>取消规则</b>（SRS FR-S-09）：交易完成之前卖家都可以取消，<b>含「开始发货」</b>；
 * 取消即终态，取消时按明细数量回补库存，售罄下架的商品随可售库存恢复为正而回到在售。
 */
@Service
public class SellerOrderService {

    /** 单页上限，与买家侧「我的订单」保持一致，防止一次拉过多订单。 */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 五状态顺序推进表：{@code placed → confirmed → packed → shipped → completed}。
     *
     * <p>刻意建成显式表而不是 {@code ordinal() + 1}：一旦将来枚举顺序调整或新增状态，
     * 隐式算术会悄悄改变业务规则，而这张表改不改、怎么改都一目了然。
     * 「交易完成」「已取消」不在表中，天然不可推进。
     */
    private static final Map<OrderStatus, OrderStatus> NEXT_STATUS = new EnumMap<>(OrderStatus.class);

    static {
        NEXT_STATUS.put(OrderStatus.placed, OrderStatus.confirmed);
        NEXT_STATUS.put(OrderStatus.confirmed, OrderStatus.packed);
        NEXT_STATUS.put(OrderStatus.packed, OrderStatus.shipped);
        NEXT_STATUS.put(OrderStatus.shipped, OrderStatus.completed);
    }

    /** 商品状态中文名：与后台商品管理页保持同一口径；未知值回退为 code，避免枚举演进时 500。 */
    private static final Map<String, String> PRODUCT_STATUS_LABELS = new LinkedHashMap<>();

    static {
        PRODUCT_STATUS_LABELS.put("active", "在售");
        PRODUCT_STATUS_LABELS.put("resumed", "在售");
        PRODUCT_STATUS_LABELS.put("frozen", "已冻结");
        PRODUCT_STATUS_LABELS.put("sold", "已售（终态）");
        PRODUCT_STATUS_LABELS.put("depleted", "售罄下架");
        PRODUCT_STATUS_LABELS.put("withdrawn", "手动下架");
    }

    private final SellerOrderRepository orders;
    private final SellerOrderItemRepository orderItemStats;
    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final ShoppingService auth;
    private final ObjectMapper mapper;

    public SellerOrderService(SellerOrderRepository orders, SellerOrderItemRepository orderItemStats,
                              OrderItemRepository orderItems, ProductRepository products,
                              CustomerRepository customers, ShoppingService auth, ObjectMapper mapper) {
        this.orders = orders;
        this.orderItemStats = orderItemStats;
        this.orderItems = orderItems;
        this.products = products;
        this.customers = customers;
        this.auth = auth;
        this.mapper = mapper;
    }

    // ==================== 14209 / 14214 两个订单查询入口 ====================

    /**
     * 卖家侧订单列表，三种用法共用同一份实现：
     * <ul>
     *   <li>{@code productId} 非空：14209 —— 按商品维度，返回涉及该商品的<b>全部订单</b>；</li>
     *   <li>{@code customerId} 非空：14214 —— 按客户维度，返回该客户的<b>全部订单</b>；</li>
     *   <li>两个都不传：卖家工作台的全部订单总览。</li>
     * </ul>
     *
     * <p>两个条件互斥而不是取交集：14209 问的是「这件商品被人下过哪些单」，
     * 14214 问的是「这个客户下过哪些单」，同时传既无业务含义也容易误用，直接拒绝更清楚。
     *
     * <p>不传维度时按「下单时间倒序 + id 倒序」分页，与买家端 {@code /api/orders} 的排序
     * 完全一致，避免同一笔订单在两边看到的顺序不同。
     */
    @Transactional(readOnly = true)
    public ObjectNode list(String session, Long productId, Long customerId, int page, int size) {
        auth.requireSession(session);
        if (productId != null && customerId != null) {
            throw bad("productId", "按商品与按客户是两个独立的查询入口，请只指定其中一个");
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<OrderEntity> found;
        if (productId != null) {
            found = orders.findByProductId(productId, pageable);
        } else if (customerId != null) {
            found = orders.findByCustomerId(customerId, pageable);
        } else {
            found = orders.findAll(pageable);
        }

        List<Long> orderIds = found.getContent().stream().map(OrderEntity::getId).toList();
        Map<Long, List<OrderItemEntity>> grouped = groupItems(orderIds);

        ArrayNode items = mapper.createArrayNode();
        for (OrderEntity order : found.getContent()) {
            items.add(orderView(order, grouped.getOrDefault(order.getId(), List.of()), productId));
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", found.getTotalElements());
        result.put("totalPages", Math.max(1, found.getTotalPages()));

        // 维度头部：让页面能显示「这是哪件商品 / 哪位客户」以及该维度的汇总
        if (productId != null) {
            result.set("product", productHeader(productId));
            result.putNull("customer");
        } else if (customerId != null) {
            result.set("customer", customerHeader(customerId));
            result.putNull("product");
        } else {
            result.putNull("product");
            result.putNull("customer");
        }
        return result;
    }

    /** 单笔订单详情（卖家视角，含客户信息与可用操作）。 */
    @Transactional(readOnly = true)
    public ObjectNode detail(String session, long orderId) {
        auth.requireSession(session);
        OrderEntity order = orders.findById(orderId)
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在"));
        return orderView(order, orderItems.findByOrderIdOrderByIdAsc(orderId), null);
    }

    // ==================== 14207 推进订单状态 ====================

    /**
     * 推进订单状态：五状态顺序推进，一次一格。
     *
     * @param target 期望推进到的状态，可为 {@code null}。为 {@code null} 时自动推进到下一格；
     *               传入时必须是「当前状态的下一格」，否则按跳过 / 回退拒绝。
     *               之所以允许显式传目标状态，是为了让「不可跳过」这条规则在接口层就能被拒绝，
     *               而不是只靠前端不提供那个按钮。
     */
    @Transactional
    public ObjectNode advance(String session, long orderId, String target) {
        auth.requireSession(session);
        OrderEntity order = orders.findById(orderId)
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在"));

        OrderStatus current = order.getStatus();
        OrderStatus next = NEXT_STATUS.get(current);
        if (next == null) {
            // 终态：交易完成不可撤销，已取消同样不可再变更
            throw conflict("订单已「" + current.label() + "」，状态不可再变更"
                    + (current == OrderStatus.completed ? "；交易完成不可撤销" : ""));
        }
        if (target != null) {
            OrderStatus requested = parseStatus(target);
            if (requested == current) {
                throw conflict("订单当前已是「" + current.label() + "」，不能原地推进");
            }
            if (requested != next) {
                throw conflict("订单状态只能顺序推进：当前「" + current.label()
                        + "」的下一步只能是「" + next.label() + "」，不能跳到「" + requested.label() + "」");
            }
        }

        // 条件更新：库里状态必须仍是我刚读到的那个，避免并发重复推进
        if (orders.changeStatus(orderId, current, next) == 0) {
            throw conflict("订单状态已被其他操作变更，请刷新后重试");
        }

        ObjectNode result = actionView(orderId, next);
        result.put("message", "订单已推进到「" + next.label() + "」");
        return result;
    }

    // ==================== 14208 卖家取消订单 ====================

    /**
     * 卖家取消订单：交易完成之前都可以取消，<b>包含「开始发货」</b>（FR-S-09 / BR-14）。
     *
     * <p>取消时按明细数量逐项回补库存，回补走既有的商品更新接口
     * {@link ProductRepository#restoreStock}——该接口已经处理了「售罄下架（depleted）的商品
     * 在库存恢复为正后自动回到在售」，同时不会碰手动下架（withdrawn）的商品，
     * 因此本类不需要、也不应该自己写库存字段。
     *
     * <p>「已取消」「交易完成」不可再次取消：前者是幂等语义下的终态，后者交易已结束不可撤销。
     */
    @Transactional
    public ObjectNode cancel(String session, long orderId) {
        auth.requireSession(session);
        OrderEntity order = orders.findById(orderId)
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "orderId", "订单不存在"));

        OrderStatus current = order.getStatus();
        if (current == OrderStatus.cancelled) {
            throw conflict("订单已取消，无需重复取消");
        }
        if (!cancellableBySeller(current)) {
            throw conflict("订单已「" + current.label() + "」，交易完成后不可取消");
        }

        // 先做状态条件更新：只有真正把状态改成「已取消」的那一次请求才回补库存，
        // 并发或重复点击不会把同一笔订单的库存回补两次
        if (orders.changeStatus(orderId, current, OrderStatus.cancelled) == 0) {
            throw conflict("订单状态已被其他操作变更，请刷新后重试");
        }

        ArrayNode restored = mapper.createArrayNode();
        int totalQuantity = 0;
        for (OrderItemEntity line : orderItems.findByOrderIdOrderByIdAsc(orderId)) {
            // 走既有商品更新接口回补库存（不改 product 表的任何字段语义）
            products.restoreStock(line.getProductId(), line.getQuantity());
            totalQuantity += line.getQuantity();
            ProductEntity product = products.findById(line.getProductId()).orElse(null);
            ObjectNode node = mapper.createObjectNode();
            node.put("productId", line.getProductId());
            node.put("productName", line.getProductName());
            node.put("quantity", line.getQuantity());
            if (product == null) {
                // 商品记录缺失（历史脏数据）：库存无处回补，如实告知而不是假装成功
                node.putNull("stock");
                node.put("statusLabel", "商品记录缺失");
            } else {
                node.put("stock", product.getStock() == null ? 0 : product.getStock());
                node.put("status", product.getStatus() == null ? null : product.getStatus().code());
                node.put("statusLabel", productStatusLabel(product.getStatus()));
            }
            restored.add(node);
        }

        ObjectNode result = actionView(orderId, OrderStatus.cancelled);
        result.set("restored", restored);
        result.put("restoredQuantity", totalQuantity);
        result.put("message", "订单已取消，共回补 " + totalQuantity + " 件商品库存");
        return result;
    }

    // ==================== 内部实现 ====================

    /** 卖家可取消 = 「交易完成」「已取消」之外的全部状态（含「开始发货」）。 */
    private boolean cancellableBySeller(OrderStatus status) {
        return status.isInProgress();
    }

    /** 推进后的状态视图：告诉前端「还能不能继续推进 / 还能不能取消」，避免前端自己算状态机。 */
    private ObjectNode actionView(long orderId, OrderStatus status) {
        OrderStatus next = NEXT_STATUS.get(status);
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        node.put("status", status.code());
        node.put("statusLabel", status.label());
        if (next == null) {
            node.putNull("nextStatus");
            node.putNull("nextStatusLabel");
        } else {
            node.put("nextStatus", next.code());
            node.put("nextStatusLabel", next.label());
        }
        node.put("advanceable", next != null);
        node.put("cancellable", cancellableBySeller(status));
        return node;
    }

    /** 订单视图：整单字段 + 明细 + 当前可执行的两个动作。 */
    private ObjectNode orderView(OrderEntity order, List<OrderItemEntity> lines, Long highlightProductId) {
        OrderStatus status = order.getStatus();
        OrderStatus next = NEXT_STATUS.get(status);

        ObjectNode node = mapper.createObjectNode();
        node.put("id", order.getId());
        node.put("orderNo", orderNo(order.getId()));
        node.put("customerId", order.getCustomerId());
        CustomerEntity customer = order.getCustomerId() == null ? null
                : customers.findById(order.getCustomerId()).orElse(null);
        node.put("customerName", customer == null ? "（客户记录缺失）" : customer.getUsername());
        node.put("customerPhone", customer == null ? "" : customer.getPhone());
        node.put("totalCents", order.getTotalCents());
        node.put("createdAt", order.getCreatedAt());
        node.put("status", status.code());
        node.put("statusLabel", status.label());
        if (next == null) {
            node.putNull("nextStatus");
            node.putNull("nextStatusLabel");
        } else {
            node.put("nextStatus", next.code());
            node.put("nextStatusLabel", next.label());
        }
        // 这两个布尔值是「卖家侧」口径，与买家侧的 cancellable（发货前才可取消）不同
        node.put("advanceable", next != null);
        node.put("cancellable", cancellableBySeller(status));

        ArrayNode items = mapper.createArrayNode();
        int totalQuantity = 0;
        for (OrderItemEntity line : lines) {
            ObjectNode item = mapper.createObjectNode();
            item.put("id", line.getId());
            item.put("productId", line.getProductId());
            item.put("productName", line.getProductName());
            item.put("unitPriceCents", line.getUnitPriceCents());
            item.put("quantity", line.getQuantity());
            item.put("subtotalCents", line.subtotalCents());
            // 按商品维度查询时，前端据此高亮「本单里的那件商品」
            item.put("highlight", highlightProductId != null
                    && Objects.equals(line.getProductId(), highlightProductId));
            items.add(item);
            totalQuantity += line.getQuantity();
        }
        node.set("items", items);
        node.put("itemCount", items.size());
        node.put("totalQuantity", totalQuantity);
        return node;
    }

    /** 14209 维度头部：商品当前库存、状态，以及「被下过多少单 / 累计多少件」。 */
    private ObjectNode productHeader(Long productId) {
        ProductEntity product = products.findById(productId)
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "productId", "商品不存在"));
        ObjectNode node = mapper.createObjectNode();
        node.put("id", product.getId());
        node.put("name", product.getName());
        node.put("priceCents", product.getPriceCents());
        node.put("stock", product.getStock() == null ? 0 : product.getStock());
        node.put("status", product.getStatus() == null ? null : product.getStatus().code());
        node.put("statusLabel", productStatusLabel(product.getStatus()));
        node.put("orderCount", orderItemStats.orderCountByProduct(productId));
        node.put("totalQuantity", orderItemStats.totalQuantityByProduct(productId));
        return node;
    }

    /** 14214 维度头部：客户基本信息（不含密码哈希）。 */
    private ObjectNode customerHeader(Long customerId) {
        CustomerEntity customer = customers.findById(customerId)
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "customerId", "客户不存在"));
        ObjectNode node = mapper.createObjectNode();
        node.put("id", customer.getId());
        node.put("username", customer.getUsername());
        node.put("phone", customer.getPhone());
        node.put("defaultPlace", customer.getDefaultPlace() == null ? "未设置" : customer.getDefaultPlace());
        node.put("registeredAt", customer.getRegisteredAt());
        return node;
    }

    /** 批量装载明细并按订单分组，避免逐单查询（N+1）。 */
    private Map<Long, List<OrderItemEntity>> groupItems(List<Long> orderIds) {
        Map<Long, List<OrderItemEntity>> grouped = new LinkedHashMap<>();
        if (orderIds.isEmpty()) return grouped;
        for (OrderItemEntity line : orderItems.findByOrderIdInOrderByIdAsc(orderIds)) {
            grouped.computeIfAbsent(line.getOrderId(), key -> new ArrayList<>()).add(line);
        }
        return grouped;
    }

    /** 订单号展示口径与买家端完全一致：自增主键左补零到 8 位。 */
    private String orderNo(Long id) {
        return id == null ? "" : String.format("%08d", id);
    }

    private String productStatusLabel(ProductStatus status) {
        if (status == null) return "未知";
        return PRODUCT_STATUS_LABELS.getOrDefault(status.code(), status.code());
    }

    /** 前端传来的目标状态码 → 枚举；非法值按 400 处理，而不是让 IllegalArgumentException 变成 500。 */
    private OrderStatus parseStatus(String value) {
        try {
            OrderStatus status = OrderStatus.of(value);
            if (status == null) throw bad("status", "请指定要推进到的订单状态");
            return status;
        } catch (IllegalArgumentException e) {
            throw bad("status", "未知的订单状态：" + value);
        }
    }

    private OrderException bad(String field, String message) {
        return new OrderException(HttpStatus.BAD_REQUEST, field, message);
    }

    private OrderException conflict(String message) {
        return new OrderException(HttpStatus.CONFLICT, "orderId", message);
    }
}
