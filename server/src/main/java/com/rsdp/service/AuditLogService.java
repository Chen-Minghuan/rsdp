package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.entity.AuditLog;
import com.rsdp.mapper.AuditLogMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.AesEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 审计日志服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    private static final Set<String> PRICE_FIELDS = Set.of("factoryPrice", "oldPrice", "newPrice");

    /**
     * 记录创建操作。
     *
     * <p>事务策略明确为 {@code REQUIRES_NEW}（见 {@link #insert} JavaDoc）。</p>
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param newValue  创建后对象
     * @param operator  操作人
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logCreate(String tableName, String recordId, Object newValue, String operator) {
        insert(tableName, recordId, "CREATE", null, newValue, operator);
    }

    /**
     * 记录更新操作。
     *
     * <p>事务策略明确为 {@code REQUIRES_NEW}（见 {@link #insert} JavaDoc）。</p>
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param oldValue  更新前对象
     * @param newValue  更新后对象
     * @param operator  操作人
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logUpdate(String tableName, String recordId, Object oldValue, Object newValue, String operator) {
        insert(tableName, recordId, "UPDATE", oldValue, newValue, operator);
    }

    /**
     * 记录复核/状态变更操作。
     *
     * <p>事务策略明确为 {@code REQUIRES_NEW}（见 {@link #insert} JavaDoc）。</p>
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param oldValue  变更前对象
     * @param newValue  变更后对象
     * @param operator  操作人
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logReview(String tableName, String recordId, Object oldValue, Object newValue, String operator) {
        insert(tableName, recordId, "REVIEW", oldValue, newValue, operator);
    }

    /**
     * 记录删除操作。
     *
     * <p>事务策略明确为 {@code REQUIRES_NEW}（见 {@link #insert} JavaDoc）。</p>
     *
     * @param tableName 表名
     * @param recordId  记录 ID
     * @param oldValue  删除前对象
     * @param operator  操作人
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logDelete(String tableName, String recordId, Object oldValue, String operator) {
        insert(tableName, recordId, "DELETE", oldValue, null, operator);
    }

    /**
     * 审计写入。
     *
     * <p>事务策略：四个 public 入口方法标注 {@code REQUIRES_NEW} 独立事务。
     * 原因：PostgreSQL 中事务内任一语句失败会导致整个事务中止，若审计与业务同事务，
     * 审计写入失败会连带业务提交失败；独立事务下审计失败仅记日志。
     * 代价是业务后续回滚时可能残留审计记录，视为「操作尝试留痕」，可接受。</p>
     */
    private void insert(String tableName, String recordId, String action,
                        Object oldValue, Object newValue, String operator) {
        AuditLog logEntry = new AuditLog();
        logEntry.setTableName(tableName);
        logEntry.setRecordId(recordId);
        logEntry.setAction(action);
        logEntry.setOldValue(sanitizeValue(oldValue));
        logEntry.setNewValue(sanitizeValue(newValue));
        logEntry.setOperator(StringUtils.hasText(operator) ? operator : SecurityOperatorContext.currentUsername());
        logEntry.setCreatedAt(LocalDateTime.now());
        try {
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("审计日志写入失败: {} {} {}", tableName, recordId, action, e);
        }
    }

    private Map<String, Object> sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            sanitizePriceFields(tree);
            return objectMapper.convertValue(tree, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("审计日志快照价格字段加密失败，将原值写入: {}", e.getMessage());
            try {
                return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ex) {
                log.warn("审计日志快照序列化失败，快照置空: {}", ex.getMessage());
                return null;
            }
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
