package com.coursewise.shopping.address;

import com.coursewise.shopping.order.OrderException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 地址簿 REST API（API-22，SRS §7.2）。
 *
 * <p>全部接口只认 {@code customer_session}，请求体不得指定 customerId；
 * 每个客户只能读写自己的地址，未登录 401、他人地址 404。
 */
@RestController
public class AddressController {
    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping("/api/addresses")
    public ObjectNode list(@CookieValue(name = "customer_session", required = false) String session) {
        return addresses.list(session);
    }

    @PostMapping("/api/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode add(@CookieValue(name = "customer_session", required = false) String session,
                          @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        return addresses.add(session, text(request.get("addressText")), bool(request.get("isDefault")));
    }

    @PatchMapping("/api/addresses/{id}")
    public ObjectNode edit(@CookieValue(name = "customer_session", required = false) String session,
                           @PathVariable long id,
                           @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        return addresses.edit(session, id, text(request.get("addressText")), bool(request.get("isDefault")));
    }

    @PostMapping("/api/addresses/{id}/default")
    public ObjectNode makeDefault(@CookieValue(name = "customer_session", required = false) String session,
                                  @PathVariable long id) {
        return addresses.makeDefault(session, id);
    }

    @DeleteMapping("/api/addresses/{id}")
    public ObjectNode remove(@CookieValue(name = "customer_session", required = false) String session,
                             @PathVariable long id) {
        return addresses.remove(session, id);
    }

    private String text(Object value) {
        return value == null ? null : value.toString().strip();
    }

    /** 未传保持原值；只允许显式布尔值，避免字符串 "false" 被当成 true。 */
    private Boolean bool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag;
        throw new OrderException(HttpStatus.BAD_REQUEST, "isDefault", "isDefault 必须是布尔值");
    }
}
