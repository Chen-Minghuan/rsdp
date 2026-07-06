package com.rsdp.dto.response;

import lombok.Data;

/**
 * 异步线程池运行时指标。
 */
@Data
public class AsyncMetricsResponse {

    private int corePoolSize;
    private int maxPoolSize;
    private int queueCapacity;
    private int activeCount;
    private int queueSize;
    private long completedTaskCount;
    private long taskCount;
    private String rejectedPolicy;
}
