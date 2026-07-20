package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.entity.AuditLog;
import com.rsdp.mapper.AuditLogMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.AesEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Set;

/**
 * 审计日志服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    private static final Set<String> PRICE_FIELDS = Set.of("factoryPrice", "oldPrice", "newPrice");
    private static final int MAX_SNAPSHOT_LENGTH = 32768;

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
        logEntry.setOldValue(truncateSnapshot(sanitizeValue(oldValue)));
        logEntry.setNewValue(truncateSnapshot(sanitizeValue(newValue)));
        logEntry.setOperator(StringUtils.hasText(operator) ? operator : SecurityOperatorContext.currentUsername());
        logEntry.setCreatedAt(LocalDateTime.now());
        auditLogWriter.write(logEntry);
    }

    private Object truncateSnapshot(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > MAX_SNAPSHOT_LENGTH) {
                return objectMapper.readTree(json.substring(0, MAX_SNAPSHOT_LENGTH) + "... [截断，原始长度=" + json.length() + "]");
            }
            return value;
        } catch (Exception e) {
            log.warn("审计日志快照截断失败: {}", e.getMessage());
            return value;
        }
    }

    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            sanitizePriceFields(tree);
            return tree;
        } catch (Exception e) {
            log.warn("审计日志快照价格字段加密失败，将原值写入: {}", e.getMessage());
            return value;
        }
    }

    private void sanitizePriceFields(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<String> fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode child = objectNode.get(fieldName);
                if (PRICE_FIELDS.contains(fieldName) && child.isNumber()) {
                    BigDecimal price = child.decimalValue();
                    objectNode.set(fieldName, objectMapper.valueToTree(AesEncryptionUtil.encrypt(price)));
                } else {
                    sanitizePriceFields(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                sanitizePriceFields(arrayNode.get(i));
            }
        }
    }
}
