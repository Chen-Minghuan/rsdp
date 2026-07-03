package com.rsdp.exception;

import lombok.Getter;

/**
 * 外部服务调用异常，用于包装 AI、向量库、存储等外部依赖失败。
 */
@Getter
public class ExternalServiceException extends RuntimeException {

    private final int code;

    public ExternalServiceException(String message) {
        this(message, null);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
        this.code = 503;
    }
}
