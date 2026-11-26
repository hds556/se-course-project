package com.coursewise.shopping.catalog;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.OptimisticLockingFailureException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<?> catalog(CatalogException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason(),"fieldErrors",e.getFieldErrors()));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null ? "操作失败" : e.getReason()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> upload(Exception e) { return bad("images","单张图片不得超过 5MB"); }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<?> input(Exception e) { return bad("form","参数类型错误，请检查整数及 JSON 格式"); }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> version(Exception e) {
        return ResponseEntity.status(409).body(Map.of("error","商品已更新，请刷新后重试"));
    }
    private ResponseEntity<?> bad(String field,String message) {
        return ResponseEntity.badRequest().body(Map.of("error",message,"fieldErrors",Map.of(field,message)));
    }
}
