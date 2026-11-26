package com.coursewise.shopping.customer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 客户侧 REST API（FR-C-04 注册、FR-C-05 登录、FR-C-07 历史下单记录）。
 *
 * <p>会话走 HttpOnly Cookie {@code customer_session}，与卖家会话（{@code seller_session}）
 * 互相独立，允许同名账号共存（T-35）。
 */
@RestController
public class CustomerController {
    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    /** FR-C-04 注册：注册成功不自动登录（T-12），需前往登录页手动登录。 */
    @PostMapping("/api/customer/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode register(@RequestBody Map<String, String> body) {
        return customers.register(body.get("username"), body.get("password"),
                body.get("phone"), body.get("defaultPlace"));
    }

    /** FR-C-05 登录：凭用户名 + 密码；失败统一提示"用户名或密码错误"（T-13）。 */
    @PostMapping("/api/customer/login")
    public ResponseEntity<Map<String, Boolean>> login(@RequestBody Map<String, String> body) {
        String token = customers.login(body.get("username"), body.get("password"));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(token, false).toString())
                .body(Map.of("ok", true));
    }

    /** 当前登录客户信息（未登录返回 401，由前端跳转登录页）。 */
    @GetMapping("/api/customer/me")
    public ObjectNode me(@CookieValue(name = "customer_session", required = false) String session) {
        return customers.me(session);
    }

    /** FR-C-05 退出登录（T-12 提供退出登录）。 */
    @PostMapping("/api/customer/logout")
    public ResponseEntity<Map<String, Boolean>> logout(
            @CookieValue(name = "customer_session", required = false) String session) {
        customers.logout(session);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", true).toString())
                .body(Map.of("ok", true));
    }

    /**
     * 旧版「历史下单记录」接口。
     *
     * <p>迭代 1~3 的成交记录来自「购买意向」表（{@code intent}）。挑战需求B 引入订单制后，
     * 买家的下单记录统一由 {@link com.coursewise.shopping.order.OrderController}
     * 的 {@code GET /api/orders} 提供（FR-C-10，含进行中 / 交易完成 / 已取消），
     * 本接口不再有数据来源，保留路由只返回空列表以兼容旧书签，避免 404。
     */
    @GetMapping("/api/customer/orders")
    public ObjectNode orders(@CookieValue(name = "customer_session", required = false) String session,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "10") int size) {
        customers.requireCustomer(session);
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.set("items", result.arrayNode());
        result.put("page", Math.max(1, page)).put("size", Math.max(1, size));
        result.put("total", 0).put("totalPages", 1);
        result.put("movedTo", "/api/orders");
        return result;
    }

    private ResponseCookie cookie(String value, boolean expired) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("customer_session", value)
                .httpOnly(true).sameSite("Strict").path("/");
        if (expired) builder.maxAge(0);
        return builder.build();
    }
}
