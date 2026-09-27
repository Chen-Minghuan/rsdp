package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("async_task")
public class AsyncTask {
    @TableId
    private String taskId;
    private String taskType;
    private String status;
    private Integer progress;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String inputData;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resultData;

    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /** 副作用幂等键（v3.3 §50.3）：重复 enqueue 返回既有任务，Worker 崩溃重试不重复建任务 */
    private String idempotencyKey;
}
