package com.rsdp.service;

import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.entity.ImageAssets;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductSubjectCropService} 单元测试。
 *
 * <p>VisionService / StorageService / Mapper 均 mock，验证裁剪触发条件与主图替换逻辑。</p>
 */
class ProductSubjectCropServiceTest {

    private VisionService visionService;
    private StorageService storageService;
    private ImageAssetsMapper imageAssetsMapper;
    private AuditLogService auditLogService;
    private ProductSubjectCropService cropService;

    @BeforeEach
    void setUp() throws Exception {
        visionService = mock(VisionService.class);
        storageService = mock(StorageService.class);
        imageAssetsMapper = mock(ImageAssetsMapper.class);
        auditLogService = mock(AuditLogService.class);
        cropService = new ProductSubjectCropService(visionService, storageService, imageAssetsMapper, auditLogService);
        ReflectionTestUtils.setField(cropService, "enabled", true);
        ReflectionTestUtils.setField(cropService, "keepOriginal", true);
        ReflectionTestUtils.setField(cropService, "minAreaRatio", 0.15);
        ReflectionTestUtils.setField(cropService, "maxAreaRatio", 0.95);
        lenient().when(storageService.store(any(InputStream.class), anyString(), anyLong(), anyString()))
            .thenAnswer(inv -> inv.getArgument(1));
    }

    /** 生成 400×400 白底、中央 200×200 灰色块的测试图（模拟白底产品图）。 */
    private byte[] buildTestImage() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 400);
        g.setColor(Color.GRAY);
        g.fillRect(100, 100, 200, 200);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void cropToSubject_shouldReturnCroppedJpeg() throws Exception {
        byte[] imageBytes = buildTestImage();
        when(visionService.detectProductSubject(any(InputStream.class)))
            .thenReturn(new ProductBoundingBox(0.25, 0.25, 0.5, 0.5));

        Optional<byte[]> result = cropService.cropToSubject(imageBytes);

        assertThat(result).isPresent();
        BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(result.get()));
        assertThat(cropped).isNotNull();
        // 裁剪后应明显小于原图（主体占 1/4 面积，含少量外扩与留白）
        assertThat(cropped.getWidth()).isLessThan(400);
        assertThat(cropped.getHeight()).isLessThan(400);
    }

    @Test
    void cropToSubject_shouldReturnEmptyWhenDisabled() throws Exception {
        ReflectionTestUtils.setField(cropService, "enabled", false);

        Optional<byte[]> result = cropService.cropToSubject(buildTestImage());

        assertThat(result).isEmpty();
        verify(visionService, never()).detectProductSubject(any());
    }

    @Test
    void cropToSubject_shouldReturnEmptyWhenNoSubjectDetected() throws Exception {
        when(visionService.detectProductSubject(any(InputStream.class))).thenReturn(null);

        Optional<byte[]> result = cropService.cropToSubject(buildTestImage());

        assertThat(result).isEmpty();
    }

    @Test
    void cropToSubject_shouldReturnEmptyWhenAreaTooLarge() throws Exception {
        when(visionService.detectProductSubject(any(InputStream.class)))
            .thenReturn(new ProductBoundingBox(0.0, 0.0, 1.0, 1.0));

        Optional<byte[]> result = cropService.cropToSubject(buildTestImage());

        assertThat(result).isEmpty();
    }

    @Test
    void cropToSubject_shouldReturnEmptyWhenAreaTooSmall() throws Exception {
        // 面积占比约 0.10，低于 min-area-ratio 0.15，视为误检
        when(visionService.detectProductSubject(any(InputStream.class)))
            .thenReturn(new ProductBoundingBox(0.3, 0.3, 0.32, 0.32));

        Optional<byte[]> result = cropService.cropToSubject(buildTestImage());

        assertThat(result).isEmpty();
    }

    @Test
    void cropToSubject_shouldReturnEmptyWhenVisionThrows() throws Exception {
        when(visionService.detectProductSubject(any(InputStream.class)))
            .thenThrow(new RuntimeException("AI API 调用失败"));

        Optional<byte[]> result = cropService.cropToSubject(buildTestImage());

        assertThat(result).isEmpty();
    }

    @Test
    void cropToSubject_shouldReturnEmptyOnUndecodableImage() {
        Optional<byte[]> result = cropService.cropToSubject("not-an-image".getBytes());

        assertThat(result).isEmpty();
    }

    @Test
    void cropAndReplacePrimary_shouldStoreCroppedAndKeepOriginal() throws Exception {
        byte[] imageBytes = buildTestImage();
        when(visionService.detectProductSubject(any(InputStream.class)))
            .thenReturn(new ProductBoundingBox(0.25, 0.25, 0.5, 0.5));
        ImageAssets primary = new ImageAssets();
        primary.setImageId("IMG-1");
        primary.setRspuId("RSPU-1");
        primary.setVariantId("VAR-1");
        primary.setImageType("white_bg");
        primary.setStoragePath("images/IMG-1.png");
        primary.setPrimary(true);
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(primary);

        Optional<byte[]> result = cropService.cropAndReplacePrimary(
            imageBytes, "RSPU-1", "VAR-1", "IMG-1", "images/IMG-1.png");

        assertThat(result).isPresent();
        // 裁剪图写入新对象键（同 imageId，扩展名改 jpg）
        verify(storageService).store(any(InputStream.class), eq("images/IMG-1.jpg"), anyLong(), eq("image/jpeg"));
        // 原图登记为 original 类型资产，仍指向原对象键
        ArgumentCaptor<ImageAssets> originalCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper).insert(originalCaptor.capture());
        ImageAssets original = originalCaptor.getValue();
        assertThat(original.getImageType()).isEqualTo("original");
        assertThat(original.getStoragePath()).isEqualTo("images/IMG-1.png");
        assertThat(original.getPrimary()).isFalse();
        assertThat(original.getWidth()).isEqualTo(400);
        assertThat(original.getHeight()).isEqualTo(400);
        // 主图改指裁剪图并回填元数据
        assertThat(primary.getStoragePath()).isEqualTo("images/IMG-1.jpg");
        assertThat(primary.getFormat()).isEqualTo("jpg");
        assertThat(primary.getWidth()).isNotNull();
        verify(imageAssetsMapper).updateById(primary);
        // 原图文件不删除
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void cropAndReplacePrimary_shouldDeleteOriginalWhenKeepOriginalFalse() throws Exception {
        ReflectionTestUtils.setField(cropService, "keepOriginal", false);
        byte[] imageBytes = buildTestImage();
        when(visionService.detectProductSubject(any(InputStream.class)))
            .thenReturn(new ProductBoundingBox(0.25, 0.25, 0.5, 0.5));
        ImageAssets primary = new ImageAssets();
        primary.setImageId("IMG-1");
        primary.setRspuId("RSPU-1");
        primary.setStoragePath("images/IMG-1.png");
        when(imageAssetsMapper.selectById("IMG-1")).thenReturn(primary);

        Optional<byte[]> result = cropService.cropAndReplacePrimary(
            imageBytes, "RSPU-1", null, "IMG-1", "images/IMG-1.png");

        assertThat(result).isPresent();
        verify(storageService).delete("images/IMG-1.png");
        verify(imageAssetsMapper, never()).insert(any(ImageAssets.class));
    }

    @Test
    void cropAndReplacePrimary_shouldFallbackWhenCropFails() throws Exception {
        when(visionService.detectProductSubject(any(InputStream.class))).thenReturn(null);

        Optional<byte[]> result = cropService.cropAndReplacePrimary(
            buildTestImage(), "RSPU-1", null, "IMG-1", "images/IMG-1.png");

        assertThat(result).isEmpty();
        verify(storageService, never()).store(any(InputStream.class), anyString(), anyLong(), anyString());
        verify(imageAssetsMapper, never()).updateById(any(ImageAssets.class));
    }
}
