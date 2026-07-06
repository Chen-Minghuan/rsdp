package com.rsdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 *
 * <p>为 {@code @Async} 方法提供有界队列、命名线程池，避免使用 Spring 默认的
 * {@code SimpleAsyncTaskExecutor} 导致无限制创建线程。</p>
 *
 * <p>拒绝策略默认使用 {@code AbortPolicy}：队列和线程池均满时直接抛出
 * {@code RejectedExecutionException}，避免 {@code CallerRunsPolicy} 阻塞 HTTP 线程。
 * 同时通过 {@link LoggingRejectedExecutionHandler} 记录 ERROR 日志，便于日志告警发现。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${rsdp.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${rsdp.async.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${rsdp.async.queue-capacity:200}")
    private int queueCapacity;

    @Value("${rsdp.async.thread-name-prefix:rsdp-async-}")
    private String threadNamePrefix;

    @Value("${rsdp.async.rejected-policy:abort}")
    private String rejectedPolicy;

    /**
     * AI 识别等后台任务使用的线程池。
     *
     * @return 有界线程池执行器
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new LoggingRejectedExecutionHandler(resolveRejectedExecutionHandler()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    private RejectedExecutionHandler resolveRejectedExecutionHandler() {
        String policy = rejectedPolicy == null || rejectedPolicy.isBlank() ? "abort" : rejectedPolicy.trim().toLowerCase();
        return switch (policy) {
            case "caller-runs" -> new ThreadPoolExecutor.CallerRunsPolicy();
            case "discard" -> new ThreadPoolExecutor.DiscardPolicy();
            case "discard-oldest" -> new ThreadPoolExecutor.DiscardOldestPolicy();
            default -> new ThreadPoolExecutor.AbortPolicy();
        };
    }
}
