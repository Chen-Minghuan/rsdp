package com.rsdp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestClient 重试拦截器，对 IO 异常进行有限次数重试。
 */
@Slf4j
@RequiredArgsConstructor
public class RetryInterceptor implements ClientHttpRequestInterceptor {

    private final int maxRetries;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        int attempts = 0;
        while (true) {
            try {
                return execution.execute(request, body);
            } catch (IOException e) {
                attempts++;
                if (attempts > maxRetries) {
                    throw e;
                }
                log.warn("外部请求失败，第 {} 次重试: {}", attempts, request.getURI());
            }
        }
    }
}
