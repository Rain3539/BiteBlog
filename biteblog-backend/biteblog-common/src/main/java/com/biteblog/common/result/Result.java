package com.biteblog.common.result;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 统一响应结果
 */
@Data
public class Result<T> {
    private int code;
    private String msg;
    private T data;
    private LocalDateTime timestamp;
    private String requestId;

    private Result() {
        this.timestamp = LocalDateTime.now();
    }

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.msg = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.msg = msg;
        return r;
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMsg());
    }

    public Result<T> requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }
}
