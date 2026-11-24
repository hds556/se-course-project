package com.coursewise.shopping.order;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 买家端 REST API：收藏、购物车、订单（SRS §7.2 API-06 ~ API-10），
 * 以及挑战需求C 的支付（API-19）、确认收货（API-20）、查询物流（API-21）。
 *
 * <p>全部路由走 {@code customer_session} 会话，未登录由
 * {@code CustomerService.requireCustomer} 抛 401，前端据此跳登录页（BR-40）。
 * 所有查询都以「登录客户」为过滤条件，不接受前端传入的 customerId，
 * 从接口形态上就杜绝越权（AC-10）。
 */
@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // ---------- 14201 收藏（API-06）----------

    @GetMapping("/api/favorites")
    public ObjectNode favorites(@CookieValue(name = "customer_session", required = false) String session) {
        return orderService.listFavorites(session);
    }

    @GetMapping("/api/favorites/{productId}")
    public ObjectNode favoriteState(@CookieValue(name = "customer_session", required = false) String session,
                                    @PathVariable long productId) {
        return orderService.favoriteState(session, productId);
    }

    /** 收藏商品；重复收藏幂等，不产生第二条记录。 */
    @PostMapping("/api/favorites/{productId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode addFavorite(@CookieValue(name = "customer_session", required = false) String session,
                                  @PathVariable long productId) {
        return orderService.addFavorite(session, productId);
    }

    @DeleteMapping("/api/favorites/{productId}")
    public ObjectNode removeFavorite(@CookieValue(name = "customer_session", required = false) String session,
                                     @PathVariable long productId) {
        return orderService.removeFavorite(session, productId);
    }

    // ---------- 14202 购物车（API-07）----------

    @GetMapping("/api/cart")
    public ObjectNode cart(@CookieValue(name = "customer_session", required = false) String session) {
        return orderService.listCart(session);
    }

    @PostMapping("/api/cart")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode addToCart(@CookieValue(name = "customer_session", required = false) String session,
                                @RequestBody(required = false) Map<String, Object> body) {
        return orderService.addToCart(session,
                whole(body == null ? null : body.get("productId")),
                integer(body == null ? null : body.get("quantity")));
    }

    /** 修改数量；数量下限 1。 */
    @PatchMapping("/api/cart/{id}")
    public ObjectNode updateQuantity(@CookieValue(name = "customer_session", required = false) String session,
                                     @PathVariable long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return orderService.updateCartQuantity(session, id, integer(body == null ? null : body.get("quantity")));
    }

    @DeleteMapping("/api/cart/{id}")
    public ObjectNode removeCartItem(@CookieValue(name = "customer_session", required = false) String session,
                                     @PathVariable long id) {
        return orderService.removeCartItem(session, id);
    }

    /** 详情页一次性取「是否已收藏 / 是否已在购物车」。 */
    @GetMapping("/api/cart/marks/{productId}")
    public ObjectNode marks(@CookieValue(name = "customer_session", required = false) String session,
                            @PathVariable long productId) {
        return orderService.productActions(session, productId);
    }

    // ---------- 14203 提交订单（API-08）----------

    /** 从购物车勾选商品提交，生成 1 笔订单；返回订单与逐商品缺货结果。 */
    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode submit(@CookieValue(name = "customer_session", required = false) String session,
                             @RequestBody(required = false) Map<String, Object> body) {
        return orderService.submit(session, cartItemIds(body == null ? null : body.get("cartItemIds")));
    }

    // ---------- 14205 我的订单（API-09）----------

    @GetMapping("/api/orders")
    public ObjectNode myOrders(@CookieValue(name = "customer_session", required = false) String session,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size) {
        return orderService.listMine(session, page, size);
    }

    @GetMapping("/api/orders/{id}")
    public ObjectNode orderDetail(@CookieValue(name = "customer_session", required = false) String session,
                                  @PathVariable long id) {
        return orderService.detail(session, id);
    }

    // ---------- 14204 客户取消订单（API-10）----------

    /** 客户取消：仅发货前可取消，立即生效、不填原因、不二次确认。 */
    @PostMapping("/api/orders/{id}/cancel")
    public ObjectNode cancel(@CookieValue(name = "customer_session", required = false) String session,
                             @PathVariable long id) {
        return orderService.cancel(session, id);
    }

    // ---------- 14257 支付订单（API-19）----------

    /** 该订单的支付选项：可付状态、金额、截止时间、四种渠道（支付页渲染用）。 */
    @GetMapping("/api/orders/{id}/payment")
    public ObjectNode paymentOptions(@CookieValue(name = "customer_session", required = false) String session,
                                     @PathVariable long id) {
        return orderService.paymentOptions(session, id);
    }

    /**
     * 发起 / 完成支付（模拟）。
     *
     * <p>请求体 {@code {"payChannel":"alipay","success":true}}：
     * <ul>
     *   <li>不传 {@code success}：只把订单置为「待支付」并记录所选渠道，返回模拟支付页地址；</li>
     *   <li>{@code success=true}：模拟支付成功，订单转「已支付」；</li>
     *   <li>{@code success=false}：模拟支付失败，订单停留「待支付」，可重试。</li>
     * </ul>
     * 重复提交按幂等处理，不产生第二笔扣款（BR-55 / AC-14）。
     */
    @PostMapping("/api/orders/{id}/pay")
    public ObjectNode pay(@CookieValue(name = "customer_session", required = false) String session,
                          @PathVariable long id,
                          @RequestBody(required = false) Map<String, Object> body) {
        String channel = text(body == null ? null : body.get("payChannel"));
        Boolean success = bool(body == null ? null : body.get("success"));
        return success == null
                ? orderService.startPayment(session, id, channel, null)
                : orderService.pay(session, id, channel, success);
    }

    // ---------- 14258 确认收货（API-20）----------

    /** 客户确认收货：「开始发货」→「交易完成」，终态不可逆，重复提交幂等。 */
    @PostMapping("/api/orders/{id}/confirm")
    public ObjectNode confirm(@CookieValue(name = "customer_session", required = false) String session,
                              @PathVariable long id) {
        return orderService.confirmReceipt(session, id);
    }

    // ---------- 14259 查询物流（API-21）----------

    /** 物流详情：单号、公司、模拟跟踪信息（已发货 / 运输中 / 已签收）。 */
    @GetMapping("/api/orders/{id}/logistics")
    public ObjectNode logistics(@CookieValue(name = "customer_session", required = false) String session,
                                @PathVariable long id) {
        return orderService.logistics(session, id);
    }

    // ---------- 参数解析 ----------

    /** 勾选的购物车项 id 列表；允许数字或数字字符串两种形态。 */
    private List<Long> cartItemIds(Object value) {
        if (value == null) throw new OrderException(HttpStatus.BAD_REQUEST, "cartItemIds", "请先勾选要下单的商品");
        if (!(value instanceof List<?> list)) {
            throw new OrderException(HttpStatus.BAD_REQUEST, "cartItemIds", "cartItemIds 必须为数组");
        }
        List<Long> ids = new ArrayList<>();
        for (Object element : list) {
            Long id = whole(element);
            if (id == null) throw new OrderException(HttpStatus.BAD_REQUEST, "cartItemIds", "购物车项编号无效");
            ids.add(id);
        }
        return ids;
    }

    private Long whole(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof Number number) return new BigDecimal(number.toString()).longValueExact();
            if (value instanceof String text && text.trim().matches("\\d{1,18}")) return Long.parseLong(text.trim());
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private Integer integer(Object value) {
        Long parsed = whole(value);
        if (parsed == null || parsed > Integer.MAX_VALUE) return null;
        return parsed.intValue();
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 布尔参数：接受 JSON 布尔与 "true"/"false" 字符串；无法识别时返回 null（按「仅发起支付」处理）。 */
    private Boolean bool(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value == null) return null;
        String text = value.toString().trim();
        if (text.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (text.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }
}
