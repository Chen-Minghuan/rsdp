package com.rsdp.service;

import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.dto.SceneDetectedProduct;
import com.rsdp.dto.response.SceneImportResult;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SceneImportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SceneImportServiceTest {

    @Mock
    private VisionService visionService;
    @Mock
    private ProductService productService;
    @Mock
    private ImageUploadValidator imageUploadValidator;

    @InjectMocks
    private SceneImportService sceneImportService;

    @BeforeEach
    void setUp() throws Exception {
        injectField("maxFileSizeMb", 10);
        injectField("maxProducts", 12);
        injectField("outputQuality", 0.9f);
    }

    private void injectField(String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = SceneImportService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(sceneImportService, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MockMultipartFile sceneFile() throws Exception {
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new MockMultipartFile("file", "scene.jpg", "image/jpeg", out.toByteArray());
    }

    private SceneDetectedProduct product(double x, String category) {
        return new SceneDetectedProduct(new ProductBoundingBox(x, 0.1, 0.3, 0.3), category, "测试产品");
    }

    @Test
    void importScene_shouldCreateEntryPerDetectedProduct() throws Exception {
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(product(0.05, "SF"), product(0.55, null)));
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), anyString()))
            .thenReturn(Map.of("rspuId", "RSPU-1", "taskId", "TASK-1", "imageIds", List.of("IMG-1")))
            .thenReturn(Map.of("rspuId", "RSPU-2", "taskId", "TASK-2", "imageIds", List.of("IMG-2")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), "TB");

        assertThat(result.getTotalProducts()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailedCount()).isZero();
        // 第一件用 AI 检测品类 SF；第二件检测品类为空，回退用户提示 TB
        assertThat(result.getProducts().get(0).getCategoryCode()).isEqualTo("SF");
        assertThat(result.getProducts().get(0).getRspuId()).isEqualTo("RSPU-1");
        assertThat(result.getProducts().get(0).getImageId()).isEqualTo("IMG-1");
        assertThat(result.getProducts().get(1).getCategoryCode()).isEqualTo("TB");
    }

    @Test
    void importScene_shouldContinueWhenSingleProductFails() throws Exception {
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(product(0.05, "SF"), product(0.55, "FS")));
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("SF")))
            .thenThrow(new RuntimeException("存储失败"));
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("FS")))
            .thenReturn(Map.of("rspuId", "RSPU-2", "taskId", "TASK-2", "imageIds", List.of("IMG-2")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getProducts().get(0).getStatus()).isEqualTo("failed");
        assertThat(result.getProducts().get(0).getError()).contains("存储失败");
        assertThat(result.getProducts().get(1).getStatus()).isEqualTo("success");
        // 无检测品类且无提示时兜底 FS（第二件本身检测出 FS，不受兜底影响）
    }

    @Test
    void importScene_shouldFallbackToDefaultCategoryFs() throws Exception {
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(product(0.05, null)));
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("FS")))
            .thenReturn(Map.of("rspuId", "RSPU-1", "taskId", "TASK-1", "imageIds", List.of("IMG-1")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        assertThat(result.getProducts().get(0).getCategoryCode()).isEqualTo("FS");
    }

    @Test
    void importScene_shouldReturnEmptyResultWhenNoProductDetected() throws Exception {
        lenient().when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of());

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        assertThat(result.getTotalProducts()).isZero();
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getProducts()).isEmpty();
    }

    @Test
    void importScene_shouldFilterAbnormalBboxArea() throws Exception {
        // 面积 <3%（噪声）和 >70%（接近整图）的框应被丢弃，正常框保留
        SceneDetectedProduct tooSmall = new SceneDetectedProduct(
            new ProductBoundingBox(0.1, 0.1, 0.1, 0.1), "SF", "噪声框");
        SceneDetectedProduct tooLarge = new SceneDetectedProduct(
            new ProductBoundingBox(0.0, 0.0, 0.95, 0.9), "SF", "整图框");
        SceneDetectedProduct normal = new SceneDetectedProduct(
            new ProductBoundingBox(0.3, 0.3, 0.3, 0.3), "TB", "正常茶几");
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(tooSmall, tooLarge, normal));
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("TB")))
            .thenReturn(Map.of("rspuId", "RSPU-1", "taskId", "TASK-1", "imageIds", List.of("IMG-1")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        assertThat(result.getTotalProducts()).isEqualTo(1);
        assertThat(result.getProducts().get(0).getCategoryCode()).isEqualTo("TB");
    }

    @Test
    void importScene_shouldKeepTighterBoxWhenOverlap() throws Exception {
        // 大框（沙发，含茶几区域）与小框（茶几）重叠：保留较小（更紧贴）的框
        SceneDetectedProduct big = new SceneDetectedProduct(
            new ProductBoundingBox(0.1, 0.1, 0.6, 0.5), "SF", "大框沙发");
        SceneDetectedProduct small = new SceneDetectedProduct(
            new ProductBoundingBox(0.2, 0.2, 0.2, 0.2), "TB", "紧贴茶几");
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(big, small));
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("TB")))
            .thenReturn(Map.of("rspuId", "RSPU-1", "taskId", "TASK-1", "imageIds", List.of("IMG-1")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        assertThat(result.getTotalProducts()).isEqualTo(1);
        assertThat(result.getProducts().get(0).getCategoryCode()).isEqualTo("TB");
    }

    @Test
    void importScene_shouldApplyRefinedBboxWhenRefineSucceeds() throws Exception {
        injectField("refineEnabled", true);
        SceneDetectedProduct coarse = product(0.1, "SF");
        // 精修返回相对粗框的内缩框（粗框中央 50%）+ 更准品类与名称
        SceneDetectedProduct refined = new SceneDetectedProduct(
            new ProductBoundingBox(0.25, 0.25, 0.5, 0.5), "TB", "精修茶几");
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(coarse));
        when(visionService.refineSceneProduct(any(InputStream.class))).thenReturn(refined);
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("TB")))
            .thenReturn(Map.of("rspuId", "RSPU-1", "taskId", "TASK-1", "imageIds", List.of("IMG-1")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        // 品类与名称被精修结果覆盖
        assertThat(result.getProducts().get(0).getCategoryCode()).isEqualTo("TB");
        assertThat(result.getProducts().get(0).getLabel()).isEqualTo("精修茶几");
        assertThat(result.getProducts().get(0).getStatus()).isEqualTo("success");
    }

    @Test
    void importScene_shouldFallbackToCoarseCropWhenRefineFails() throws Exception {
        injectField("refineEnabled", true);
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(product(0.1, "SF")));
        when(visionService.refineSceneProduct(any(InputStream.class)))
            .thenThrow(new com.rsdp.exception.ExternalServiceException("AI 超时"));
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("SF")))
            .thenReturn(Map.of("rspuId", "RSPU-1", "taskId", "TASK-1", "imageIds", List.of("IMG-1")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        // 精修失败不阻断：回退粗框正常建档
        assertThat(result.getProducts().get(0).getStatus()).isEqualTo("success");
        assertThat(result.getProducts().get(0).getCategoryCode()).isEqualTo("SF");
    }

    @Test
    void importScene_shouldFallbackToCoarseCropWhenNotSingleFurniture() throws Exception {
        injectField("refineEnabled", true);
        when(visionService.detectSceneProducts(any(InputStream.class), anyInt()))
            .thenReturn(List.of(product(0.1, "SF")));
        when(visionService.refineSceneProduct(any(InputStream.class))).thenReturn(null);
        when(productService.createEntryFromStream(any(InputStream.class), anyString(), anyLong(), eq("SF")))
            .thenReturn(Map.of("rspuId", "RSPU-1", "taskId", "TASK-1", "imageIds", List.of("IMG-1")));

        SceneImportResult result = sceneImportService.importScene(sceneFile(), null);

        assertThat(result.getProducts().get(0).getStatus()).isEqualTo("success");
    }
}
