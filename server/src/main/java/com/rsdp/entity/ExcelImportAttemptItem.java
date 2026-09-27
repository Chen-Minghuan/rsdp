package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Import AttemptItem（设计文档 v3.3 §49.2，P0-F）：一次 Attempt 内一个 ProductGroup 的
 * Runtime 结果与恢复 Checkpoint 的唯一载体。
 *
 * <p>状态语义（§49.2.1）：</p>
 * <ul>
 *   <li>{@code pending}：尚未处理；{@code running}：当前 Worker 正在处理；</li>
 *   <li>{@code imported}：该 Group 所有允许写入内容成功；</li>
 *   <li>{@code partial}：Product 成功，但存在被拒绝的 Variant / Quote；</li>
 *   <li>{@code rejected}：确定性业务规则拒绝整个 ProductGroup（原样重试不会改变的终态）；</li>
 *   <li>{@code failed}：可重试的技术失败；</li>
 *   <li>{@code skipped}：策略明确跳过。</li>
 * </ul>
 *
 * <p>「已完成」判定只依赖 status 终态（imported / partial / rejected / skipped），
 * 不依赖内存状态（§49.2.2）；领域写入与终态更新同一数据库事务（§31/§50.2）。</p>
 */
@Data
@TableName("excel_import_attempt_item")
public class ExcelImportAttemptItem {

    /** 终态集合判定（§49.2.2）。 */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_IMPORTED = "imported";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED = "skipped";

    private String attemptId;
    private String groupId;

    private String batchId;
    private Long previewRevision;

    private String status;

    /** 可恢复执行阶段（§49.2.2：validated/identity_resolved/persisting/committed） */
    private String checkpoint;

    private Integer variantSuccessCount;
    private Integer variantFailedCount;
    private Integer quoteSuccessCount;
    private Integer quoteFailedCount;

    /** 组级结果摘要：行计数 / 行级失败定位 / 策略跳过说明（有界，详见 AttemptService） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resultSummary;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
