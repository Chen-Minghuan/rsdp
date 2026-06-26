package com.rsdp.common;

import lombok.Data;

/**
 * 统一响应结果。
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> {

    public static final int CODE_OK = 200;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_INTERNAL_ERROR = 500;

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setCode(CODE_OK);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(String message) {
        return error(CODE_INTERNAL_ERROR, message);
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> badRequest(String message) {
        return error(CODE_BAD_REQUEST, message);
    }

    public static <T> Result<T> notFound(String message) {
        return error(CODE_NOT_FOUND, message);
    }
}
