package com.rsdp.service;

import com.rsdp.entity.AuditLog;
import com.rsdp.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审计日志异步写入器。
 *
 * <p>从 {@link AuditLogService} 分离，确保 {@code @Async} 代理生效，避免审计日志
 * 写入阻塞主业务线程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogMapper auditLogMapper;

    /**
     * 异步写入审计日志。
     *
     * @param logEntry 审计日志实体
     */
    @Async("auditLogExecutor")
    public void write(AuditLog logEntry) {
        try {
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("审计日志写入失败: {} {} {}", logEntry.getTableName(), logEntry.getRecordId(), logEntry.getAction(), e);
        }
    }
}
