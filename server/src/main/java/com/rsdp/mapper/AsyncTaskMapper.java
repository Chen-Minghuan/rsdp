package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    /**
     * 原子认领待处理任务：仅当任务仍处于 pending 状态时置为 processing。
     *
     * @param taskId 任务 ID
     * @return 受影响行数；0 表示任务已被其他执行器认领或已进入终态
     */
    @Update("UPDATE async_task SET status = 'processing', progress = 10 WHERE task_id = #{taskId} AND status = 'pending'")
    int claimPendingTask(@Param("taskId") String taskId);
}
