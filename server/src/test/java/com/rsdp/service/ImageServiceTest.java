package com.rsdp.service;

import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link ImageService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private StorageService storageService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private DesignOrderItemMapper designOrderItemMapper;

    @InjectMocks
    private ImageService imageService;

    private MockedStatic<SecurityOperatorContext> securityContextMock;

    @BeforeEach
    void authenticate() {
        securityContextMock = Mockito.mockStatic(SecurityOperatorContext.class);
        securityContextMock.when(SecurityOperatorContext::isAuthenticated).thenReturn(true);
    }

    @AfterEach
    void clearAuthentication() {
        if (securityContextMock != null) {
            securityContextMock.close();
        }
    }

    @Test
    void loadImageResource_shouldReturnLoadedImage() throws Exception {
        ImageAssets asset = new ImageAssets();
        asset.setImageId("IMG-TEST01");
        asset.setRspuId("RSPU-TEST01");
        asset.setStoragePath("images/IMG-TEST01.jpg");
        asset.setFormat("jpg");

        when(imageAssetsMapper.selectById("IMG-TEST01")).thenReturn(asset);
        when(dataScopeHelper.canAccessRspu("RSPU-TEST01")).thenReturn(true);
        when(storageService.get("images/IMG-TEST01.jpg"))
            .thenReturn(new ByteArrayInputStream("fake-image".getBytes()));

        ImageService.LoadedImage loaded = imageService.loadImageResource("IMG-TEST01");

        assertThat(loaded).isNotNull();
        assertThat(loaded.resource()).isNotNull();
        assertThat(loaded.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void loadImageResource_shouldDenyUnauthorizedUser() {
        ImageAssets asset = new ImageAssets();
        asset.setImageId("IMG-TEST01");
        asset.setRspuId("RSPU-TEST01");
        asset.setStoragePath("images/IMG-TEST01.jpg");
        asset.setFormat("jpg");

        when(imageAssetsMapper.selectById("IMG-TEST01")).thenReturn(asset);
        when(dataScopeHelper.canAccessRspu("RSPU-TEST01")).thenReturn(false);

        assertThatThrownBy(() -> imageService.loadImageResource("IMG-TEST01"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("图片不存在");
    }

    @Test
    void loadImageResource_shouldThrowWhenNotFound() {
        when(imageAssetsMapper.selectById("IMG-NOTEXIST")).thenReturn(null);

        assertThatThrownBy(() -> imageService.loadImageResource("IMG-NOTEXIST"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("图片不存在");
    }

    @Test
    void resolveContentType_shouldReturnCorrectMimeType() {
        assertThat(imageService.resolveContentType("photo.jpg")).isEqualTo("image/jpeg");
        assertThat(imageService.resolveContentType("photo.jpeg")).isEqualTo("image/jpeg");
        assertThat(imageService.resolveContentType("photo.png")).isEqualTo("image/png");
        assertThat(imageService.resolveContentType("photo.webp")).isEqualTo("image/webp");
        assertThat(imageService.resolveContentType("photo.gif")).isEqualTo("image/gif");
        assertThat(imageService.resolveContentType("photo.bmp")).isEqualTo("image/bmp");
        assertThat(imageService.resolveContentType("file.txt")).isEqualTo("application/octet-stream");
    }
}
