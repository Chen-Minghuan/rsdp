package com.rsdp.exception;

/**
 * Import Attempt 执行租约已易主（设计文档 v3.3 §49.1/§50.1）。
 *
 * <p>心跳续约或带租约守卫的写入发现 {@code lease_owner} 不再是当前 Worker 时抛出，
 * 当前执行者必须立即中止：领域写入随当前行事务回滚，不改批次/Attempt 状态
 * （新持有者应已完成接管，由它继续执行与收尾）。</p>
 */
public class AttemptLeaseLostException extends RuntimeException {

    public AttemptLeaseLostException(String attemptId, String owner) {
        super("Import Attempt 执行租约已易主，当前执行者中止: attemptId=" + attemptId + ", owner=" + owner);
    }
}
