package com.rsdp.exception;

import lombok.Getter;

/**
 * 业务异常，通常由非法入参或业务规则校验触发。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
