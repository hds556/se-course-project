package com.coursewise.shopping.order;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单域异常：除整体错误文案外，可携带<b>逐商品</b>的缺货明细。
 *
 * <p>提交订单采用「逐商品校验」而非整单拒绝（BR-06 / AC-04）：某件商品库存不足时，
 * 该商品被标记缺货且不进入本次下单，其余商品照常提交，因此需要把每个商品的判定结果
 * 一并回给前端，让页面能逐条提示。
 *
 * <p>刻意<b>不</b>继承 {@code ResponseStatusException}：那个异常自带 {@code detail} 字段，
 * 会被 Spring 内建的 {@code ResponseStatusExceptionResolver} 抢先处理，走 problem+json 格式，
 * 本类附加的 {@code itemErrors} 就会丢失。这里用独立异常类型，由 {@link OrderErrors} 统一转换。
 */
public class OrderException extends RuntimeException {

    private final HttpStatusCode status;
    private final Map<String, String> fieldErrors = new LinkedHashMap<>();
    /** 逐商品明细（key = productId），仅提交订单时使用。 */
    private final Map<String, String> itemErrors = new LinkedHashMap<>();

    public OrderException(HttpStatus status, String field, String message) {
        super(message);
        this.status = status;
        if (field != null) fieldErrors.put(field, message);
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public Map<String, String> getItemErrors() {
        return itemErrors;
    }

    /** 记录某商品不可下单的原因（缺货 / 已下架）。 */
    public OrderException itemError(Long productId, String message) {
        itemErrors.put(String.valueOf(productId), message);
        return this;
    }

    /** 用于「部分商品缺货」场景：整体 409，但带上逐商品结果。 */
    public static OrderException items(HttpStatus status, String message, Map<String, String> itemErrors) {
        OrderException error = new OrderException(status, null, message);
        error.itemErrors.putAll(itemErrors);
        return error;
    }
}
