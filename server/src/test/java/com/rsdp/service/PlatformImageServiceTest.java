package com.rsdp.service;

import com.rsdp.dto.response.CmsImageUploadResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlatformImageService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PlatformImageServiceTest {

    @Mock
    private ImageUploadValidator imageUploadValidator;

    @Mock
    private StorageService storageService;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PlatformImageService platformImageService;

    @Test
    void uploadShouldStoreAndPersistImageAsset() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
            "file", "banner.png", "image/png", new byte[]{1, 2, 3});
        when(storageService.store(any(MockMultipartFile.class), anyString())).thenReturn("cms/IMG-1.png");

        CmsImageUploadResponse response = platformImageService.upload(file);

        assertThat(response.getImageId()).startsWith("IMG-");
        assertThat(response.getUrl()).isEqualTo("/api/v1/images/" + response.getImageId());

        ArgumentCaptor<ImageAssets> captor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(captor.capture());
        assertThat(captor.getValue().getImageType()).isEqualTo("cms");
        assertThat(captor.getValue().getRspuId()).isNull();
        assertThat(captor.getValue().getStoragePath()).isEqualTo("cms/IMG-1.png");
        verify(auditLogService).logCreate(eq("image_assets"), anyString(), any(ImageAssets.class), any());
    }

    @Test
    void uploadShouldRejectInvalidFile() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "evil.exe", "application/octet-stream", new byte[]{1});
        doThrow(new BusinessException("请上传图片格式文件"))
            .when(imageUploadValidator).validate(any(MockMultipartFile.class), anyLong());

        assertThatThrownBy(() -> platformImageService.upload(file))
            .isInstanceOf(BusinessException.class);
        verify(imageAssetsMapper, never()).insert(any(ImageAssets.class));
    }
}
