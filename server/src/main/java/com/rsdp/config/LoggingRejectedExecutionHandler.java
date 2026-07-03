package com.rsdp.config;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 带日志记录的线程池拒绝策略包装器。
 *
 * <p>在任务被拒绝时输出 ERROR 日志，记录当前线程池状态（活跃线程数、队列大小、
 * 核心/最大线程数、已完成任务数），便于接入日志告警。</p>
 */
@Slf4j
public class LoggingRejectedExecutionHandler implements RejectedExecutionHandler {

    private final RejectedExecutionHandler delegate;
    private final String delegateName;

    public LoggingRejectedExecutionHandler(RejectedExecutionHandler delegate) {
        this.delegate = delegate;
        this.delegateName = delegate != null ? delegate.getClass().getSimpleName() : "unknown";
    }

    public String getDelegateName() {
        return delegateName;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        log.error(
            "异步任务线程池队列已满，任务被拒绝。活跃线程数：{}, 队列大小：{}, 核心线程数：{}, 最大线程数：{}, 已完成任务数：{}, 任务类：{}",
            executor.getActiveCount(),
            executor.getQueue().size(),
            executor.getCorePoolSize(),
            executor.getMaximumPoolSize(),
            executor.getCompletedTaskCount(),
            r != null ? r.getClass().getName() : "null"
        );
        delegate.rejectedExecution(r, executor);
    }
}
