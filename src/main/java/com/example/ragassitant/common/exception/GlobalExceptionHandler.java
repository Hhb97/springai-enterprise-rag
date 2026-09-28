package com.example.ragassitant.common.exception;

import com.example.ragassitant.interfaces.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("静态资源未找到: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("NOT_FOUND", "资源不存在: " + e.getResourcePath()));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        log.warn("业务异常: [{}] {}", e.getCode(), e.getMessage());
        HttpStatus status = "NOT_FOUND".equals(e.getCode()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 有界线程池已满（{@code AgentExecutors.stream()} 拒绝）→ 429。
     *
     * <p>改造前线程池无界，这种情况表现为线程数失控直到 OOM；现在是明确、可重试的「服务繁忙」。
     * 必须写在 RuntimeException 处理器之前被匹配到（Spring 按异常类型最具体者优先）。
     */
    @ExceptionHandler(java.util.concurrent.RejectedExecutionException.class)
    public ResponseEntity<ApiResponse<Void>> handleRejected(java.util.concurrent.RejectedExecutionException e) {
        log.warn("🚦 请求被拒绝（线程池已满）: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5")
                .body(ApiResponse.fail("SERVER_BUSY", "服务繁忙：当前并发问答数已达上限，请稍后重试"));
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