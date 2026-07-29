package com.example.ragassitant.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = true;
        r.code = "OK";
        r.data = data;
        r.traceId = MDC.get("traceId");
        return r;
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        ApiResponse<T> r = ok(data);
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = false;
        r.code = code;
        r.message = message;
        r.traceId = MDC.get("traceId");
        return r;
    }

    public static <T> ApiResponse<T> fail(String message) {
        return fail("BIZ_ERROR", message);
    }
}