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
 * 买家端 REST API：收藏、购物车、订单（SRS §7.2 API-06 ~ API-10）。
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
}
