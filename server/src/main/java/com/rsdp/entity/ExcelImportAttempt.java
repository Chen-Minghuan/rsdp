package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Import Attempt（设计文档 v3.3 §48/§49，P0-F）：对某一 immutable Preview Revision
 * 使用某一 Import Policy 发起的一次逻辑执行。
 *
 * <p>不可变字段（INSERT 后禁止更改）：{@code policy_schema_version / policy_payload /
 * policy_hash_version / policy_hash}（§49）；Worker 与 Recovery 只读本表的 Policy
 * Snapshot，{@code excel_import_batch.import_policy} 只是 UI 草稿。</p>
 *
 * <p>幂等（§50）：{@code uk_import_attempt(batch_id, preview_revision,
 * policy_hash_version, policy_hash)} 保证同一逻辑请求只有一个 Attempt；
 * Replay-first 分支顺序见 §50.0。</p>
 */
@Data
@TableName("excel_import_attempt")
public class ExcelImportAttempt {

    /** §49.2.3 Attempt 业务终态。 */
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_PARTIAL_SUCCESS = "partial_success";
    public static final String OUTCOME_BUSINESS_REJECTED = "business_rejected";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    @TableId
    private String attemptId;

    private String batchId;
    private Long previewRevision;

    /** Policy Schema 版本（§47.1.5），当前固定 1 */
    private Integer policySchemaVersion;

    /** 默认值已完整物化的不可变 Policy 正文（§47.1.5） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String policyPayload;

    /** Policy Hash 协议版本（§47.1.6），当前固定 1 */
    private Integer policyHashVersion;

    /** policy_hash_version + policy_schema_version + canonical(policy_payload) 的 SHA-256 */
    private String policyHash;

    /** pending / running / done / failed */
    private String status;

    /** 仅 status=done 时非空：success / partial_success / business_rejected（§49.2.3） */
    private String outcome;

    /** done 时同事务物化的结果摘要（§49.2.3），done Replay 原样返回 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resultSummary;

    /** 当前驱动本 Attempt 的异步任务（task_type=excel_import） */
    private String taskId;

    private Integer retryCount;

    /** 当前执行 Worker 标识（§49.1 lease） */
    private String leaseOwner;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime leaseExpiresAt;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private String createdBy;
    private LocalDateTime createdAt;

    private String errorCode;
    private String errorMessage;
}
