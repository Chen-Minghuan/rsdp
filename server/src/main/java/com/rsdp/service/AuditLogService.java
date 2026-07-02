package com.rsdp.service;

import com.rsdp.entity.AuditLog;
import com.rsdp.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 记录创建操作。
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param newValue  创建后对象
     * @param operator  操作人
     */
    public void logCreate(String tableName, String recordId, Object newValue, String operator) {
        insert(tableName, recordId, "CREATE", null, newValue, operator);
    }

    /**
     * 记录更新操作。
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param oldValue  更新前对象
     * @param newValue  更新后对象
     * @param operator  操作人
     */
    public void logUpdate(String tableName, String recordId, Object oldValue, Object newValue, String operator) {
        insert(tableName, recordId, "UPDATE", oldValue, newValue, operator);
    }

    /**
     * 记录复核/状态变更操作。
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param oldValue  变更前对象
     * @param newValue  变更后对象
     * @param operator  操作人
     */
    public void logReview(String tableName, String recordId, Object oldValue, Object newValue, String operator) {
        insert(tableName, recordId, "REVIEW", oldValue, newValue, operator);
    }

    /**
     * 记录删除操作。
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param oldValue  删除前对象
     * @param operator  操作人
     */
    public void logDelete(String tableName, String recordId, Object oldValue, String operator) {
        insert(tableName, recordId, "DELETE", oldValue, null, operator);
    }

    private void insert(String tableName, String recordId, String action,
                        Object oldValue, Object newValue, String operator) {
        AuditLog logEntry = new AuditLog();
        logEntry.setTableName(tableName);
        logEntry.setRecordId(recordId);
        logEntry.setAction(action);
        logEntry.setOldValue(oldValue);
        logEntry.setNewValue(newValue);
        logEntry.setOperator(operator);
        logEntry.setCreatedAt(LocalDateTime.now());
        try {
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("审计日志写入失败: {} {} {}", tableName, recordId, action, e);
        }
    }
}
