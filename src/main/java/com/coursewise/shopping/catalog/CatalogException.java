package com.coursewise.shopping.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

public class CatalogException extends ResponseStatusException {
    private final Map<String, String> fieldErrors;
    public CatalogException(HttpStatus status, String field, String message) {
        super(status, message);
        fieldErrors = Map.of(field, message);
    }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
}
