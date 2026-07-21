package com.rsdp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestClient 重试拦截器。
 *
 * <p>对 IO 异常、5xx 响应与 429（限流）进行有限次数重试，重试间隔按指数退避
 * （200ms 起步，倍增至 2s 封顶），避免外部服务抖动时立即失败，
 * 也避免固定间隔重试叠加服务端压力。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RetryInterceptor implements ClientHttpRequestInterceptor {

    private static final long INITIAL_BACKOFF_MS = 200;
    private static final long MAX_BACKOFF_MS = 2000;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final int maxRetries;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        int attempt = 0;
        long backoffMs = INITIAL_BACKOFF_MS;
        while (true) {
            try {
                ClientHttpResponse response = execution.execute(request, body);
                if (!isRetryable(response) || attempt >= maxRetries) {
                    return response;
                }
                attempt++;
                log.warn("外部请求返回 {}，第 {} 次重试: {}", response.getStatusCode(), attempt, request.getURI());
                response.close();
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                attempt++;
                log.warn("外部请求失败，第 {} 次重试: {}", attempt, request.getURI());
            }
            sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
    }

    /**
     * 判断响应是否可重试：5xx 服务端错误或 429 限流。
     */
    private boolean isRetryable(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().is5xxServerError()
            || response.getStatusCode().value() == HTTP_TOO_MANY_REQUESTS;
    }

    private void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("重试等待被中断", e);
        }
    }
}
