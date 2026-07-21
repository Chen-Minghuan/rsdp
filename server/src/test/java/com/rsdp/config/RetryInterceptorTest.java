package com.rsdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RetryInterceptor} 单元测试。
 */
class RetryInterceptorTest {

    private ClientHttpResponse responseWithStatus(HttpStatus status) throws IOException {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return response;
    }

    @Test
    void intercept_shouldRetryOn429() throws IOException {
        ClientHttpResponse tooManyRequests = responseWithStatus(HttpStatus.TOO_MANY_REQUESTS);
        ClientHttpResponse ok = responseWithStatus(HttpStatus.OK);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(tooManyRequests, ok);

        RetryInterceptor interceptor = new RetryInterceptor(2);
        ClientHttpResponse result = interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(execution, times(2)).execute(any(), any());
    }

    @Test
    void intercept_shouldRetryOn5xx() throws IOException {
        ClientHttpResponse serverError = responseWithStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        ClientHttpResponse ok = responseWithStatus(HttpStatus.OK);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(serverError, ok);

        RetryInterceptor interceptor = new RetryInterceptor(2);
        ClientHttpResponse result = interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(execution, times(2)).execute(any(), any());
    }

    @Test
    void intercept_shouldNotRetryOn4xxOtherThan429() throws IOException {
        ClientHttpResponse badRequest = responseWithStatus(HttpStatus.BAD_REQUEST);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(badRequest);

        RetryInterceptor interceptor = new RetryInterceptor(2);
        ClientHttpResponse result = interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(execution, times(1)).execute(any(), any());
    }

    @Test
    void intercept_shouldReturnLastResponseWhenRetriesExhausted() throws IOException {
        ClientHttpResponse tooManyRequests = responseWithStatus(HttpStatus.TOO_MANY_REQUESTS);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(tooManyRequests);

        RetryInterceptor interceptor = new RetryInterceptor(1);
        ClientHttpResponse result = interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(execution, times(2)).execute(any(), any());
    }
}
