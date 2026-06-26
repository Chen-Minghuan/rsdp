package com.rsdp.exception;

/**
 * 资源不存在异常。
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(404, message);
    }
}
