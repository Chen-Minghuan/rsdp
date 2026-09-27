package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    /**
     * 按副作用幂等键查找既有任务（v3.3 §50.3：重复 enqueue 返回既有任务）。
     *
     * @param taskType        任务类型
     * @param idempotencyKey  幂等键
     * @return 既有任务；不存在返回 null
     */
    @Select("SELECT * FROM async_task WHERE task_type = #{taskType}"
        + " AND idempotency_key = #{idempotencyKey}")
    AsyncTask selectByIdempotencyKey(@Param("taskType") String taskType,
                                     @Param("idempotencyKey") String idempotencyKey);

    /**
     * 重置失败任务为 pending 以复用（同一幂等键的重试不新建任务行，§50.3）。
     *
     * @return 影响行数；仅 failed 状态可复位
     */
    @Update("UPDATE async_task SET status = 'pending', progress = 0, error_message = NULL,"
        + " input_data = CAST(#{inputData} AS jsonb), completed_at = NULL"
        + " WHERE task_id = #{taskId} AND status = 'failed'")
    int resetFailedForRetry(@Param("taskId") String taskId, @Param("inputData") String inputData);

    /**
     * 原子认领待处理任务：仅当任务仍处于 pending 状态时置为 processing。
     *
     * @param taskId 任务 ID
     * @return 受影响行数；0 表示任务已被其他执行器认领或已进入终态
     */
    @Update("UPDATE async_task SET status = 'processing', progress = 10 WHERE task_id = #{taskId} AND status = 'pending'")
    int claimPendingTask(@Param("taskId") String taskId);
}
