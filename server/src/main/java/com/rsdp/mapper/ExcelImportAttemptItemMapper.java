package com.rsdp.mapper;

import com.rsdp.entity.ExcelImportAttemptItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@link ExcelImportAttemptItem} Mapper（设计文档 v3.3 §49.2，P0-F）。
 *
 * <p>不继承 BaseMapper（复合主键 attempt_id + group_id）。写入语义：</p>
 * <ul>
 *   <li>{@link #selectForUpdate}：组执行前锁定（§31/§50.2，终态组直接跳过——
 *       Recovery 不重复建数据）；</li>
 *   <li>{@link #applyRowProgress}：行事务内累加计数（增量 UPDATE，
 *       与该行领域写入同事务提交/回滚）；</li>
 *   <li>{@link #finalizeItem}：组终态物化（终态判定只看 status，§49.2.2）。</li>
 * </ul>
 */
@Mapper
public interface ExcelImportAttemptItemMapper {

    /**
     * 插入 AttemptItem（创建 Attempt 时随 Attempt 同事务批量插入，状态 pending）。
     */
    @Insert("INSERT INTO excel_import_attempt_item ("
        + " attempt_id, batch_id, preview_revision, group_id, status)"
        + " VALUES (#{attemptId}, #{batchId}, #{previewRevision}, #{groupId}, 'pending')")
    int insert(ExcelImportAttemptItem item);

    /**
     * 组执行前锁定读取（§50.2：SELECT attempt_item FOR UPDATE；
     * 终态 imported/partial/rejected/skipped 的组由调用方直接跳过）。
     */
    @Select("SELECT * FROM excel_import_attempt_item"
        + " WHERE attempt_id = #{attemptId} AND group_id = #{groupId}"
        + " FOR UPDATE")
    ExcelImportAttemptItem selectForUpdate(@Param("attemptId") String attemptId,
                                           @Param("groupId") String groupId);

    /** 按 Attempt 列出全部 Item（结果聚合用，按 group_id 升序保证确定性）。 */
    @Select("SELECT * FROM excel_import_attempt_item"
        + " WHERE attempt_id = #{attemptId} ORDER BY group_id ASC")
    List<ExcelImportAttemptItem> listByAttempt(@Param("attemptId") String attemptId);

    /**
     * 标记组开始处理（pending → running；幂等：已 running 不重复刷新 started_at）。
     * 带租约守卫：当前 Worker 不再是 Attempt 的 lease_owner 时影响 0 行。
     */
    @Update("UPDATE excel_import_attempt_item SET"
        + " status = 'running', checkpoint = 'validated',"
        + " started_at = COALESCE(started_at, now()), updated_at = now()"
        + " WHERE attempt_id = #{attemptId} AND group_id = #{groupId}"
        + "   AND status IN ('pending', 'running')"
        + "   AND EXISTS (SELECT 1 FROM excel_import_attempt a"
        + "               WHERE a.attempt_id = #{attemptId} AND a.status = 'running'"
        + "                 AND a.lease_owner = #{leaseOwner})")
    int markRunning(@Param("attemptId") String attemptId, @Param("groupId") String groupId,
                    @Param("leaseOwner") String leaseOwner);

    /**
     * 组重跑复位（§49.2.1/§50.2）：failed（技术失败重试）/ running（崩溃遗留，租约接管）
     * → running，摘要与计数覆盖为「已提交行聚合基线」（由调用方从旧摘要的
     * committedRows 计算：恢复行不重跑、不重算，未提交行重新执行）。
     * 带租约守卫：当前 Worker 不再是 lease_owner 时影响 0 行。
     */
    @Update("UPDATE excel_import_attempt_item SET"
        + " status = 'running', checkpoint = 'validated',"
        + " variant_success_count = #{variantSuccess}, variant_failed_count = 0,"
        + " quote_success_count = #{quoteSuccess}, quote_failed_count = #{quoteFailed},"
        + " result_summary = CAST(#{resultSummary} AS jsonb),"
        + " started_at = now(), completed_at = NULL, updated_at = now()"
        + " WHERE attempt_id = #{attemptId} AND group_id = #{groupId}"
        + "   AND status IN ('failed', 'running')"
        + "   AND EXISTS (SELECT 1 FROM excel_import_attempt a"
        + "               WHERE a.attempt_id = #{attemptId} AND a.status = 'running'"
        + "                 AND a.lease_owner = #{leaseOwner})")
    int resetForRerun(@Param("attemptId") String attemptId, @Param("groupId") String groupId,
                      @Param("variantSuccess") int variantSuccess,
                      @Param("quoteSuccess") int quoteSuccess,
                      @Param("quoteFailed") int quoteFailed,
                      @Param("resultSummary") String resultSummary,
                      @Param("leaseOwner") String leaseOwner);

    /**
     * 行级进度累加（行事务内调用：与该行领域写入同事务提交/回滚，§31）。
     * 计数只增不减；result_summary 由调用方整体重算后覆盖（单 Worker 串行执行同一 Attempt）。
     */
    @Update("UPDATE excel_import_attempt_item SET"
        + " variant_success_count = variant_success_count + #{variantSuccessDelta},"
        + " variant_failed_count = variant_failed_count + #{variantFailedDelta},"
        + " quote_success_count = quote_success_count + #{quoteSuccessDelta},"
        + " quote_failed_count = quote_failed_count + #{quoteFailedDelta},"
        + " checkpoint = #{checkpoint},"
        + " result_summary = CAST(#{resultSummary} AS jsonb),"
        + " updated_at = now()"
        + " WHERE attempt_id = #{attemptId} AND group_id = #{groupId}"
        + "   AND status = 'running'"
        + "   AND EXISTS (SELECT 1 FROM excel_import_attempt a"
        + "               WHERE a.attempt_id = #{attemptId} AND a.status = 'running'"
        + "                 AND a.lease_owner = #{leaseOwner})")
    int applyRowProgress(@Param("attemptId") String attemptId,
                         @Param("groupId") String groupId,
                         @Param("variantSuccessDelta") int variantSuccessDelta,
                         @Param("variantFailedDelta") int variantFailedDelta,
                         @Param("quoteSuccessDelta") int quoteSuccessDelta,
                         @Param("quoteFailedDelta") int quoteFailedDelta,
                         @Param("checkpoint") String checkpoint,
                         @Param("resultSummary") String resultSummary,
                         @Param("leaseOwner") String leaseOwner);

    /**
     * 组终态物化（§49.2.1/§49.2.2）：running → imported/partial/rejected/failed/skipped，
     * checkpoint='committed'（failed 为 'persisting' 现场保留）。只在 running 时生效，
     * 终态不可改写（Replay-first：Recovery 扫到终态直接跳过）。
     */
    @Update("UPDATE excel_import_attempt_item SET"
        + " status = #{status}, checkpoint = #{checkpoint},"
        + " result_summary = CAST(#{resultSummary} AS jsonb),"
        + " completed_at = now(), updated_at = now()"
        + " WHERE attempt_id = #{attemptId} AND group_id = #{groupId}"
        + "   AND status IN ('running', 'pending')"
        + "   AND EXISTS (SELECT 1 FROM excel_import_attempt a"
        + "               WHERE a.attempt_id = #{attemptId} AND a.status = 'running'"
        + "                 AND a.lease_owner = #{leaseOwner})")
    int finalizeItem(@Param("attemptId") String attemptId,
                     @Param("groupId") String groupId,
                     @Param("status") String status,
                     @Param("checkpoint") String checkpoint,
                     @Param("resultSummary") String resultSummary,
                     @Param("leaseOwner") String leaseOwner);
}
