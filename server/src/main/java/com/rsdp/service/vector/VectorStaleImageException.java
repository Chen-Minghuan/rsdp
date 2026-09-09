package com.rsdp.service.vector;

/**
 * 图片在编码期间被更新或删除，向量写入被丢弃（防旧写保护）。
 *
 * <p>属正常并发结果而非故障：调用方应丢弃本次向量结果，
 * 不补建图片、不写孤儿向量；新内容会由后续录入/重建流程重新编码。</p>
 */
public class VectorStaleImageException extends RuntimeException {

    public VectorStaleImageException(String message) {
        super(message);
    }
}
