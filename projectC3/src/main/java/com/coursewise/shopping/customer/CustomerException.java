package com.coursewise.shopping.customer;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 客户模块业务异常：携带 HTTP 状态与可选的字段级错误（供注册表单定位提示）。 */
public class CustomerException extends ResponseStatusException {
    private final Map<String, String> fieldErrors;

    public CustomerException(HttpStatus status, String field, String message) {
        super(status, message);
        fieldErrors = field == null ? Map.of() : Map.of(field, message);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
