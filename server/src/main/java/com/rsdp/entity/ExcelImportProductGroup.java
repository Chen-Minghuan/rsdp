package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel 导入 Preview 产品聚合输入（设计文档 v3.3 §21）：immutable input。
 *
 * <p>回答「Revision N 中，这个逻辑产品被确认成什么标准化数据？」；
 * 不回答执行期状态——所有 runtime 状态 / 计数 / Checkpoint / 结果摘要统一归
 * §49.2 {@code excel_import_attempt_item}（P0-F），本表禁止出现 status /
 * checkpoint / *_count / result_summary / started_at / completed_at（§21.1）。</p>
 *
 * <p>{@code normalized_payload} 只允许保存非敏感 / 普通级别数据；采购成本等敏感值
 * 只能以 Sensitive Ref 出现（§24.2 {@code excel_import_sensitive_value}，B2/B3 落地）。</p>
 */
@Data
@TableName("excel_import_product_group")
public class ExcelImportProductGroup {

    @TableId
    private String groupId;

    private String batchId;
    private Long previewRevision;

    /** 组内稳定键（Revision 内唯一，Issue Target / Attempt 的稳定引用） */
    private String productGroupKey;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String normalizedPayload;

    /** payload_hash 协议版本（§47.1.6），当前固定 1 */
    private Integer payloadHashVersion;

    /** 对 §47.1.2 PayloadHashMaterial 的 SHA-256 */
    private String payloadHash;

    private String normalizerVersion;
    private String mappingHash;

    private LocalDateTime createdAt;
}
