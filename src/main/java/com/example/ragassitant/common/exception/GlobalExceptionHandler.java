package com.example.ragassitant.common.exception;

import com.example.ragassitant.interfaces.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        log.warn("业务异常: [{}] {}", e.getCode(), e.getMessage());
        HttpStatus status = "NOT_FOUND".equals(e.getCode()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常: {}", e.getMessage(), e);
        String msg = e.getMessage() == null ? "服务内部错误" : e.getMessage();
        String notFoundKeyword = "不存在";
        HttpStatus status;
        String code;
        if (msg.contains(notFoundKeyword) || msg.contains("not found")) {
            status = HttpStatus.NOT_FOUND;
            code = "NOT_FOUND";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "INTERNAL_ERROR";
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(code, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("SYSTEM_ERROR", "服务繁忙，请稍后重试"));
    }
}