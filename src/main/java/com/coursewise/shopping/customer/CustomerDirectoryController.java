package com.coursewise.shopping.customer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卖家端客户档案 REST API（FR-S-13 查看客户列表、FR-S-14 查看客户购买历史）。
 *
 * <p>新增文件，不改动任何既有控制器。身份校验走 {@code seller_session}，与
 * {@code /api/admin/products}、{@code /api/admin/trades} 等卖家接口一致；
 * 未登录由 {@code ShoppingService.requireSession} 抛 401，前端据此跳登录页。
 *
 * <p>路由沿用卖家端 {@code /api/admin/customers} 前缀，不复用旧版 {@code /api/intents}
 * 一类路径——那些已在 {@code ShoppingController} 中统一返回 410。
 */
@RestController
public class CustomerDirectoryController {
    private final CustomerDirectoryService directory;

    public CustomerDirectoryController(CustomerDirectoryService directory) {
        this.directory = directory;
    }

    /** FR-S-13 客户列表：全部注册客户，按注册时间倒序、支持翻页。 */
    @GetMapping("/api/admin/customers")
    public ObjectNode customers(@CookieValue(name = "seller_session", required = false) String session,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return directory.list(session, page, size);
    }

    /** FR-S-14 客户购买历史：某客户的终态记录，字段与买家端对齐。 */
    @GetMapping("/api/admin/customers/{id}/orders")
    public ObjectNode purchases(@CookieValue(name = "seller_session", required = false) String session,
                                @PathVariable long id,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return directory.purchases(session, id, page, size);
    }
}
