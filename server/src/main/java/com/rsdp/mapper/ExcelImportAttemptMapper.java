package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@link ExcelImportAttempt} Mapper（设计文档 v3.3 §48-§50，P0-F）。
 *
 * <p>不可变策略字段（policy_schema_version / policy_payload / policy_hash_version /
 * policy_hash）没有 UPDATE 入口（§49）；状态推进只走本接口的 CAS 语句：</p>
 * <ul>
 *   <li>{@link #claimForExecution}：pending / failed / running+lease 过期 → running
 *       （§49.1 / §50.1，failed 重试 retry_count+1）；</li>
 *   <li>{@link #heartbeat}：运行期心跳续约（仅当仍持有 lease）；</li>
 *   <li>{@link #markFailed} / {@link #markDone}：终态落库（done 必须同事务携带
 *       outcome + result_summary，§49.2.3）。</li>
 * </ul>
 */
@Mapper
public interface ExcelImportAttemptMapper extends BaseMapper<ExcelImportAttempt> {

    /**
     * Replay-first 查找（§50.0 第 4 步）：按 batch + revision + policy hash 定位既有 Attempt。
     *
     * @return 命中返回唯一 Attempt（uk_import_attempt 保证），否则 null
     */
    @Select("SELECT * FROM excel_import_attempt"
        + " WHERE batch_id = #{batchId} AND preview_revision = #{previewRevision}"
        + " AND policy_hash_version = #{policyHashVersion} AND policy_hash = #{policyHash}")
    ExcelImportAttempt selectByPolicyKey(@Param("batchId") String batchId,
                                         @Param("previewRevision") long previewRevision,
                                         @Param("policyHashVersion") int policyHashVersion,
                                         @Param("policyHash") String policyHash);

    /**
     * 原子抢占执行租约（§49.1 / §50.1）：pending / failed / running 且 lease 已过期 →
     * running；failed 重试时 retry_count+1。running 且 lease 未过期 / done 返回 0。
     *
     * @param attemptId   Attempt ID
     * @param leaseOwner  执行 Worker 标识（异步任务 ID）
     * @param leaseSeconds 租约时长（秒）
     * @return 影响行数；1 = 抢占成功，0 = 已有活跃执行或已终态
     */
    @Update("UPDATE excel_import_attempt SET"
        + " status = 'running',"
        + " lease_owner = #{leaseOwner},"
        + " last_heartbeat_at = now(),"
        + " lease_expires_at = now() + (#{leaseSeconds} || ' seconds')::interval,"
        + " started_at = COALESCE(started_at, now()),"
        + " completed_at = NULL,"
        + " retry_count = retry_count + CASE WHEN status = 'failed' THEN 1 ELSE 0 END,"
        + " error_code = NULL, error_message = NULL"
        + " WHERE attempt_id = #{attemptId}"
        + "   AND (status IN ('pending', 'failed')"
        + "        OR (status = 'running' AND lease_expires_at < now()))")
    int claimForExecution(@Param("attemptId") String attemptId,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("leaseSeconds") long leaseSeconds);

    /**
     * 运行期心跳续约（§49.1）：仅当本 Worker 仍持有 lease 且处于 running 时生效。
     *
     * @return 影响行数；0 表示 lease 已易主或已离开 running（调用方应中止执行）
     */
    @Update("UPDATE excel_import_attempt SET"
        + " last_heartbeat_at = now(),"
        + " lease_expires_at = now() + (#{leaseSeconds} || ' seconds')::interval"
        + " WHERE attempt_id = #{attemptId} AND status = 'running'"
        + "   AND lease_owner = #{leaseOwner}")
    int heartbeat(@Param("attemptId") String attemptId,
                  @Param("leaseOwner") String leaseOwner,
                  @Param("leaseSeconds") long leaseSeconds);

    /**
     * 技术失败终态（§49.2.3：failed 只表示尚未完成逻辑执行的技术失败，可重试同一 Attempt）。
     * 仅在 running / pending 时生效（done 不可回退）。
     */
    @Update("UPDATE excel_import_attempt SET"
        + " status = 'failed', outcome = NULL,"
        + " error_code = #{errorCode}, error_message = #{errorMessage},"
        + " lease_owner = NULL, lease_expires_at = NULL, completed_at = now()"
        + " WHERE attempt_id = #{attemptId} AND status IN ('pending', 'running')"
        + "   AND (lease_owner = #{leaseOwner} OR lease_owner IS NULL)")
    int markFailed(@Param("attemptId") String attemptId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    /**
     * done 终态物化（§49.2.3）：outcome + result_summary 必须同事务写入；
     * 仅 running 可推进 done（防重复 finalize）。
     */
    @Update("UPDATE excel_import_attempt SET"
        + " status = 'done', outcome = #{outcome},"
        + " result_summary = CAST(#{resultSummary} AS jsonb),"
        + " lease_owner = NULL, lease_expires_at = NULL, completed_at = now()"
        + " WHERE attempt_id = #{attemptId} AND status = 'running'"
        + "   AND lease_owner = #{leaseOwner}")
    int markDone(@Param("attemptId") String attemptId,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("outcome") String outcome,
                 @Param("resultSummary") String resultSummary);

    /**
     * 换绑驱动任务（重试/恢复时新建异步任务后回写 task_id）。
     */
    @Update("UPDATE excel_import_attempt SET task_id = #{taskId}"
        + " WHERE attempt_id = #{attemptId} AND status <> 'done'")
    int rebindTask(@Param("attemptId") String attemptId, @Param("taskId") String taskId);
}
