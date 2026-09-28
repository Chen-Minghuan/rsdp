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

    /** 图片 AI 识别专用池并发数（核心=最大，固定并发上限，避免突发打满 AI 接口限流）。 */
    @Value("${rsdp.async.image-recognition.pool-size:4}")
    private int imageRecognitionPoolSize;

    /** 图片 AI 识别专用池排队容量（需覆盖单批最大导入行数 500 + 手工录入余量）。 */
    @Value("${rsdp.async.image-recognition.queue-capacity:1000}")
    private int imageRecognitionQueueCapacity;

    @Value("${rsdp.async.image-recognition.thread-name-prefix:rsdp-vision-}")
    private String imageRecognitionThreadNamePrefix;

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

    /**
     * 图片 AI 识别（产品录入/Excel 导入逐行识别）专用线程池。
     *
     * <p>与通用 {@code taskExecutor} 隔离：批量识别（单批可达数百张图）不再占满
     * 户型分析、向量重建、文档/Excel 批次等其他后台任务的执行位置。</p>
     *
     * <p>核心线程数 = 最大线程数 = {@code pool-size}，即固定并发上限；
     * 队列满时 abort 抛异常，由投递方（dispatchSafely）把任务标记为 failed 即时反馈。</p>
     *
     * @return 图片识别线程池执行器
     */
    @Bean(name = "imageRecognitionExecutor")
    public ThreadPoolTaskExecutor imageRecognitionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(imageRecognitionPoolSize);
        executor.setMaxPoolSize(imageRecognitionPoolSize);
        executor.setQueueCapacity(imageRecognitionQueueCapacity);
        executor.setThreadNamePrefix(imageRecognitionThreadNamePrefix);
        executor.setRejectedExecutionHandler(
            new LoggingRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 审计日志异步写入专用线程池。
     *
     * <p>与后台任务线程池隔离，避免 AI 识别等重任务挤占审计日志写入。</p>
     *
     * @return 审计日志线程池执行器
     */
    @Bean(name = "auditLogExecutor")
    public ThreadPoolTaskExecutor auditLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("rsdp-audit-");
        executor.setRejectedExecutionHandler(new LoggingRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy()));
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
