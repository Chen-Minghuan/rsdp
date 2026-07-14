package com.rsdp.exception;

/**
 * 无权访问异常（HTTP 403 语义）。
 */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(403, message);
    }
}
