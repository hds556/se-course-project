package com.coursewise.shopping.trade;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 交易模块 REST API（V2.0）。
 *
 * <p>买家侧使用 {@code customer_session}，卖家侧使用 {@code seller_session}，两套会话互相独立。
 * 路由刻意不复用旧版 {@code /api/intents} 与 {@code /api/admin/trade/**}——那些路径已在
 * {@code ShoppingController} 中统一返回 410，避免新旧语义混淆。
 */
@RestController
public class TradeController {
    private final TradeService trade;

    public TradeController(TradeService trade) {
        this.trade = trade;
    }

    // ---------- 买家：提交购买意向（22303 / 22304）----------

    /** 未登录返回 401，由前端跳转客户登录页。 */
    @PostMapping("/api/customer/intents")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode submit(@CookieValue(name = "customer_session", required = false) String session,
                             @RequestBody(required = false) Map<String, Object> body) {
        return trade.submitIntent(session, body == null ? null : whole(body.get("productId")));
    }

    /** 当前客户进行中的下单记录（排队中 / 交易中）。 */
    @GetMapping("/api/customer/intents")
    public ObjectNode mine(@CookieValue(name = "customer_session", required = false) String session) {
        return trade.myIntents(session);
    }

    // ---------- 卖家：交易工作台 ----------

    @GetMapping("/api/admin/trades")
    public ArrayNode workbench(@CookieValue(name = "seller_session", required = false) String session) {
        return trade.workbench(session);
    }

    @GetMapping("/api/admin/trades/{id}")
    public ObjectNode workbenchItem(@CookieValue(name = "seller_session", required = false) String session,
                                    @PathVariable long id) {
        return trade.workbenchItem(id, session);
    }

    /** 22272 发起交易：强制取队首。 */
    @PostMapping("/api/admin/trades/{id}/start")
    public ObjectNode start(@CookieValue(name = "seller_session", required = false) String session,
                            @PathVariable long id) {
        return trade.startTrade(session, id);
    }

    /** 22274 标记交易成功。 */
    @PostMapping("/api/admin/trades/{id}/success")
    public ObjectNode success(@CookieValue(name = "seller_session", required = false) String session,
                              @PathVariable long id) {
        return trade.markSuccess(session, id);
    }

    /** 22276 标记交易失败：disposition = void（作废）/ requeue（重新排队）。 */
    @PostMapping("/api/admin/trades/{id}/fail")
    public ObjectNode fail(@CookieValue(name = "seller_session", required = false) String session,
                           @PathVariable long id,
                           @RequestBody(required = false) Map<String, Object> body) {
        return trade.markFail(session, id, body == null ? null : text(body.get("disposition")));
    }

    // ---------- 卖家：手动下架（22268）/ 售罄补货与重新上架（22270）----------

    @PostMapping("/api/admin/products/{id}/withdraw")
    public ObjectNode withdraw(@CookieValue(name = "seller_session", required = false) String session,
                               @PathVariable long id,
                               @RequestBody(required = false) Map<String, Object> body) {
        return trade.withdraw(session, id, body == null ? null : text(body.get("disposition")));
    }

    @PostMapping("/api/admin/products/{id}/restock")
    public ObjectNode restock(@CookieValue(name = "seller_session", required = false) String session,
                              @PathVariable long id,
                              @RequestBody(required = false) Map<String, Object> body) {
        return trade.restock(session, id, body == null ? null : integer(body.get("stock")));
    }

    @PostMapping("/api/admin/products/{id}/relist")
    public ObjectNode relist(@CookieValue(name = "seller_session", required = false) String session,
                             @PathVariable long id) {
        return trade.relist(session, id);
    }

    // ---------- 卖家：历史商品 ----------

    @GetMapping("/api/admin/product-history")
    public ArrayNode history(@CookieValue(name = "seller_session", required = false) String session) {
        return trade.productHistory(session);
    }

    private Long whole(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && text.matches("\\d+")) return Long.parseLong(text);
        return null;
    }

    private Integer integer(Object value) {
        Long parsed = whole(value);
        return parsed == null ? null : Math.toIntExact(parsed);
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }
}
