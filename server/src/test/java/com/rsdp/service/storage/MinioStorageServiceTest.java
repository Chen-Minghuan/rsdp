package com.rsdp.service.storage;

import com.rsdp.config.properties.StorageProperties;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InternalException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MinioStorageService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    @Mock
    private MinioClient minioClient;

    private MinioStorageService minioStorageService;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        minioStorageService = new MinioStorageService(properties, minioClient);
    }

    @Test
    void exists_shouldReturnTrueWhenObjectFound() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(null);

        assertThat(minioStorageService.exists("images/IMG-001.jpg")).isTrue();
    }

    @Test
    void exists_shouldReturnFalseWhenNoSuchKey() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException exception = mock(ErrorResponseException.class);
        when(exception.errorResponse()).thenReturn(errorResponse);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(exception);

        assertThat(minioStorageService.exists("images/IMG-NOTEXIST.jpg")).isFalse();
    }

    @Test
    void exists_shouldThrowWhenErrorIsNotNoSuchKey() throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("AccessDenied");
        ErrorResponseException exception = mock(ErrorResponseException.class);
        when(exception.errorResponse()).thenReturn(errorResponse);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(exception);

        assertThatThrownBy(() -> minioStorageService.exists("images/IMG-001.jpg"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("MinIO 状态检查失败");
    }

    @Test
    void exists_shouldThrowWhenMinioException() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
            .thenThrow(new InternalException("连接失败", null));

        assertThatThrownBy(() -> minioStorageService.exists("images/IMG-001.jpg"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("MinIO 状态检查失败");
    }
}
