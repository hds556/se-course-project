package com.coursewise.shopping.seller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 卖家端订单处理 REST API（14207 / 14208 / 14209 / 14214，SRS §7.2 卖家订单接口）。
 *
 * <p>身份校验统一走 {@code seller_session} + {@code ShoppingService.requireSession}，
 * 与 {@code /api/admin/products}、{@code /api/admin/customers} 等卖家接口一致；
 * 未登录返回 401，前端据此跳卖家登录页（BR-40）。
 *
 * <p><b>路由设计</b>：两个查询入口（14209 按商品、14214 按客户）合并为同一资源
 * {@code GET /api/admin/orders} 的两个过滤参数，而不是两条各自实现的路径——
 * 它们的返回结构完全一致，合并后前端只需要一套渲染逻辑，也避免了「同一份订单在
 * 两个接口里字段不一样」这类不一致。订单本身就是卖家的工作对象，因此统一挂在
 * {@code /api/admin/orders} 下，与买家侧的 {@code /api/orders} 在路径上明确区分。
 *
 * <p>所有错误沿用订单域已有的 {@code OrderException} + {@code OrderErrors}，
 * 响应体与买家侧同构（{@code error} / {@code fieldErrors} / {@code itemErrors}），
 * 前端可以直接复用同一套错误处理。
 */
@RestController
public class SellerOrderController {

    private final SellerOrderService sellerOrders;

    public SellerOrderController(SellerOrderService sellerOrders) {
        this.sellerOrders = sellerOrders;
    }

    /**
     * 卖家订单列表。
     *
     * <ul>
     *   <li>{@code productId}：14209 查看商品订单记录——涉及该商品的全部订单；</li>
     *   <li>{@code customerId}：14214 查看客户购买历史——该客户的全部订单；</li>
     *   <li>都不传：全部订单总览。</li>
     * </ul>
     */
    @GetMapping("/api/admin/orders")
    public ObjectNode list(@CookieValue(name = "seller_session", required = false) String session,
                           @RequestParam(required = false) Long productId,
                           @RequestParam(required = false) Long customerId,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int size) {
        return sellerOrders.list(session, productId, customerId, page, size);
    }

    /** 单笔订单详情（卖家视角）。 */
    @GetMapping("/api/admin/orders/{id}")
    public ObjectNode detail(@CookieValue(name = "seller_session", required = false) String session,
                             @PathVariable long id) {
        return sellerOrders.detail(session, id);
    }

    /**
     * 14207 推进订单状态：一次进一格。
     *
     * <p>请求体可选 {@code {"to":"confirmed"}}。不传则自动推进到下一状态；
     * 传了就必须等于下一状态，否则按「跳过 / 回退」拒绝（409）。
     */
    @PostMapping("/api/admin/orders/{id}/advance")
    public ObjectNode advance(@CookieValue(name = "seller_session", required = false) String session,
                              @PathVariable long id,
                              @RequestBody(required = false) Map<String, Object> body) {
        return sellerOrders.advance(session, id, text(body == null ? null : body.get("to")));
    }

    /** 14208 卖家取消订单：交易完成前（含已发货）可取消，库存按明细数量回补。 */
    @PostMapping("/api/admin/orders/{id}/cancel")
    public ObjectNode cancel(@CookieValue(name = "seller_session", required = false) String session,
                             @PathVariable long id) {
        return sellerOrders.cancel(session, id);
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
