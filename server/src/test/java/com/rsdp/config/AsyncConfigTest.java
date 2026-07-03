package com.rsdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AsyncConfig} 单元测试。
 */
class AsyncConfigTest {

    @Test
    void taskExecutor_shouldUseAbortPolicyByDefault() throws Exception {
        AsyncConfig config = createConfigWithDefaults("abort");
        ThreadPoolTaskExecutor executor = config.taskExecutor();

        RejectedExecutionHandler handler = executor.getThreadPoolExecutor().getRejectedExecutionHandler();
        assertThat(handler).isInstanceOf(LoggingRejectedExecutionHandler.class);

        LoggingRejectedExecutionHandler loggingHandler = (LoggingRejectedExecutionHandler) handler;
        assertThat(loggingHandler.getDelegateName()).isEqualTo("AbortPolicy");
    }

    @Test
    void taskExecutor_shouldSupportCallerRunsPolicy() throws Exception {
        AsyncConfig config = createConfigWithDefaults("caller-runs");

        ThreadPoolTaskExecutor executor = config.taskExecutor();
        RejectedExecutionHandler handler = executor.getThreadPoolExecutor().getRejectedExecutionHandler();
        assertThat(handler).isInstanceOf(LoggingRejectedExecutionHandler.class);
        assertThat(((LoggingRejectedExecutionHandler) handler).getDelegateName()).isEqualTo("CallerRunsPolicy");
    }

    @Test
    void taskExecutor_shouldRejectAfterShutdown() throws Exception {
        AsyncConfig config = createConfigWithDefaults("abort");
        ThreadPoolTaskExecutor executor = config.taskExecutor();
        executor.shutdown();

        assertThatThrownBy(() -> executor.execute(() -> {}))
            .isInstanceOf(RejectedExecutionException.class);
    }

    private AsyncConfig createConfigWithDefaults(String rejectedPolicy) throws Exception {
        AsyncConfig config = new AsyncConfig();
        setField(config, "corePoolSize", 1);
        setField(config, "maxPoolSize", 1);
        setField(config, "queueCapacity", 1);
        setField(config, "threadNamePrefix", "test-async-");
        setField(config, "rejectedPolicy", rejectedPolicy);
        return config;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = AsyncConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
