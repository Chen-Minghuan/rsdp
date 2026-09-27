package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感 Preview 值实体（设计文档 v3.3 §24.2，Flyway V14）。
 *
 * <p>采购成本等敏感值不进入 {@code normalized_payload / resolution_value} 明文，
 * 统一落本表：ciphertext（可恢复，项目统一加密体系）+ value_hmac（等值摘要，
 * HMAC-SHA-256，§47.1.7）；Payload / Resolution 中只保存 Sensitive Ref。</p>
 *
 * <p>Key Rotation（§42.5）：{@code value_hmac + hmac_key_version} 是创建该 Revision 时的
 * 固定 Hash Material，保留期内禁止就地重算或覆盖；新值永远使用当前 Active HMAC Key，
 * 旧 Key 仅保留验证能力。</p>
 */
@Data
@TableName("excel_import_sensitive_value")
public class ExcelImportSensitiveValue {

    @TableId
    private String sensitiveValueId;

    private String batchId;
    private Long previewRevision;

    private String groupId;

    /** 敏感值在 payload 中的路径（PayloadHashMaterial 敏感值条目的排序键，§47.1.2） */
    private String valuePath;

    private String ciphertext;
    private Integer encryptionKeyVersion;

    private String valueHmac;
    private Integer hmacKeyVersion;

    private String dataClassification;

    private LocalDateTime createdAt;
    /** 保留期截止（§44）；超期由受限 GC 随整个未 hold batch 的快照一并清理 */
    private LocalDateTime retentionUntil;
}
