package com.coursewise.shopping.order;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单域异常 → JSON。
 *
 * <p>提交订单的「逐商品缺货」结果需要单独回传：响应体除整体 {@code error} 外，
 * 还带 {@code itemErrors}（key = productId），前端据此把缺货商品置红并保留其勾选，
 * 其余商品正常下单（BR-06 / AC-04）。
 */
@RestControllerAdvice
public class OrderErrors {

    @ExceptionHandler(OrderException.class)
    public ResponseEntity<?> handle(OrderException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage() == null ? "操作失败" : e.getMessage());
        body.put("fieldErrors", e.getFieldErrors());
        body.put("itemErrors", e.getItemErrors());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
