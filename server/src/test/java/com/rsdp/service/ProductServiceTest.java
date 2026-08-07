package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link ProductService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private AsyncTaskProcessor asyncTaskProcessor;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private DictService dictService;

    @Mock
    private RspuCodeService rspuCodeService;

    @Mock
    private RskuCodeService rskuCodeService;

    @Mock
    private RspuVariantService rspuVariantService;

    @Mock
    private ProductSubjectCropService subjectCropService;

    @Mock
    private VisionService visionService;

    private final ImageUploadValidator imageUploadValidator = new ImageUploadValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProductService productService;

    @BeforeEach
    void setUp() throws Exception {
        setField("maxFileSize", "20MB");
        setField("imageUploadValidator", imageUploadValidator);
        setField("objectMapper", objectMapper);
        setField("storageService", storageService);
        setField("auditLogService", auditLogService);
        setField("dictService", dictService);
        setField("rspuCodeService", rspuCodeService);
        setField("rskuCodeService", rskuCodeService);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ProductService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(productService, value);
    }

    private List<CategoryDict> categoryDicts() {
        return List.of(
            createDict("category", "FS", "座椅"),
            createDict("category", "DT", "桌子")
        );
    }

    private CategoryDict createDict(String dictType, String dictCode, String dictName) {
        CategoryDict dict = new CategoryDict();
        dict.setDictType(dictType);
        dict.setDictCode(dictCode);
        dict.setDictName(dictName);
        return dict;
    }

    @Test
    void createEntry_shouldRejectDuplicateImageByContentHash() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        // 库内已有同内容图片（对应已有产品）
        ImageAssets dup = new ImageAssets();
        dup.setImageId("IMG-DUP");
        dup.setRspuId("RSPU-DUP");
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(dup);
        RspuMaster dupRspu = new RspuMaster();
        dupRspu.setRspuId("RSPU-DUP");
        dupRspu.setProductName("扶摇沙发");
        dupRspu.setRspuCode("FS-WJ-001-M");
        when(rspuMapper.selectById("RSPU-DUP")).thenReturn(dupRspu);

        assertThatThrownBy(() -> productService.createEntry(List.of(image), null))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("已录入过")
            .hasMessageContaining("扶摇沙发")
            .hasMessageContaining("FS-WJ-001-M");
        verify(rspuMapper, org.mockito.Mockito.never()).insert(any(RspuMaster.class));
    }

    @Test
    void createEntry_withForce_shouldSkipDuplicateCheck() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", "fake-image".getBytes()
        );
        ImageAssets dup = new ImageAssets();
        dup.setImageId("IMG-DUP");
        dup.setRspuId("RSPU-DUP");
        lenient().when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(dup);
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        Map<String, Object> result = productService.createEntry(List.of(image), null, true);

        assertThat(result).containsKeys("taskId", "rspuId");
        verify(rspuMapper, times(1)).insert(any(RspuMaster.class));
        // 落库的图片资产应携带内容哈希
        ArgumentCaptor<List<ImageAssets>> imageCaptor = ArgumentCaptor.forClass(List.class);
        verify(imageAssetsMapper).insertBatch(imageCaptor.capture());
        assertThat(imageCaptor.getValue().get(0).getContentHash()).isNotBlank();
    }

    @Test
    void createEntry_shouldCreateDraftAndTriggerAsyncTask() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        Map<String, Object> result = productService.createEntry(List.of(image), null);

        assertThat(result).containsKeys("taskId", "rspuId", "imageIds", "message");
        assertThat(result.get("imageIds")).asList().hasSize(1);

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getStatus()).isEqualTo("processing");
        assertThat(rspuCaptor.getValue().getCategoryCode()).isEqualTo("FS");

        ArgumentCaptor<List<ImageAssets>> imageCaptor = ArgumentCaptor.forClass(List.class);
        verify(imageAssetsMapper, times(1)).insertBatch(imageCaptor.capture());
        assertThat(imageCaptor.getValue()).hasSize(1);
        ImageAssets capturedImage = imageCaptor.getValue().get(0);
        assertThat(capturedImage.getFormat()).isEqualTo("jpg");
        assertThat(capturedImage.getAiProcessed()).isFalse();
        assertThat(capturedImage.getStoragePath()).startsWith("images/");
        assertThat(capturedImage.getPrimary()).isTrue();
        assertThat(capturedImage.getImageType()).isEqualTo("white_bg");

        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(1)).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("pending");

        verify(storageService, times(1)).store(any(), anyString());
        verify(asyncTaskProcessor, times(1))
            .processProductEntry(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createEntry_withMultipleImages_shouldCreateOneRspuAndMultipleImages() throws Exception {
        MockMultipartFile primary = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        MockMultipartFile detail = new MockMultipartFile(
            "image", "chair-detail.jpg", "image/jpeg", "fake-detail".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        Map<String, Object> result = productService.createEntry(List.of(primary, detail), "DT");

        assertThat(result).containsKeys("taskId", "rspuId", "imageIds", "message");
        assertThat(result.get("imageIds")).asList().hasSize(2);

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getCategoryCode()).isEqualTo("DT");
        assertThat(rspuCaptor.getValue().getCategoryPath()).contains("桌子");
        ArgumentCaptor<List<ImageAssets>> imageListCaptor = ArgumentCaptor.forClass(List.class);
        verify(imageAssetsMapper, times(1)).insertBatch(imageListCaptor.capture());
        assertThat(imageListCaptor.getValue()).hasSize(2);
        verify(asyncTaskMapper, times(1)).insert(any(AsyncTask.class));
        verify(asyncTaskProcessor, times(1))
            .processProductEntry(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createEntry_shouldRejectEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
            "image", "empty.jpg", "image/jpeg", new byte[0]
        );

        assertThatThrownBy(() -> productService.createEntry(List.of(emptyFile), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请上传图片文件");

        verifyNoInteractions(rspuMapper, asyncTaskMapper, imageAssetsMapper, asyncTaskProcessor);
    }

    @Test
    void createEntry_shouldRejectNonImageFile() {
        MockMultipartFile textFile = new MockMultipartFile(
            "image", "readme.txt", "text/plain", "hello".getBytes()
        );

        assertThatThrownBy(() -> productService.createEntry(List.of(textFile), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片格式");
    }

    @Test
    void createEntry_shouldRejectEmptyImageList() {
        assertThatThrownBy(() -> productService.createEntry(List.of(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请至少上传一张图片");

        verifyNoInteractions(rspuMapper, asyncTaskMapper, imageAssetsMapper, asyncTaskProcessor);
    }

    @Test
    void createEntry_shouldRejectInvalidCategoryCode() {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());

        assertThatThrownBy(() -> productService.createEntry(List.of(image), "XX"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("品类不存在");

        verifyNoInteractions(rspuMapper, asyncTaskMapper, imageAssetsMapper, asyncTaskProcessor);
    }

    @Test
    void createEntry_shouldNormalizeCategoryCodeToUpperCase() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        productService.createEntry(List.of(image), "fs");

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).insert(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getCategoryCode()).isEqualTo("FS");
    }

    @Test
    void createEntry_shouldDeleteStoredFilesWhenTransactionRollsBack() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            productService.createEntry(List.of(image), null);

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            for (TransactionSynchronization sync : syncs) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            verify(storageService).delete("images/IMG-XXX.jpg");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void createEntry_shouldNotDeleteStoredFilesWhenTransactionCommits() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-XXX.jpg");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            productService.createEntry(List.of(image), null);

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            for (TransactionSynchronization sync : syncs) {
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }

            verify(storageService, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void createEntryFromStream_shouldDeleteFileWhenTransactionRollsBack() throws Exception {
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString()))
            .thenReturn("images/IMG-STREAM.jpg");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            productService.createEntryFromStream(
                new ByteArrayInputStream("fake-image".getBytes()), "chair.jpg", 100, null, null
            );

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            for (TransactionSynchronization sync : syncs) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            verify(storageService).delete("images/IMG-STREAM.jpg");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void createEntryFromStream_shouldNotDeleteFileWhenTransactionCommits() throws Exception {
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString()))
            .thenReturn("images/IMG-STREAM.jpg");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            productService.createEntryFromStream(
                new ByteArrayInputStream("fake-image".getBytes()), "chair.jpg", 100, null, null
            );

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            for (TransactionSynchronization sync : syncs) {
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }

            verify(storageService, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void createManualEntry_shouldCreateRspuAndDefaultVariant() throws Exception {
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-001");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("mc");
        request.setProductLevel("a");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        Map<String, Object> result = productService.createManualEntry(request, null);

        assertThat(result).containsKeys("rspuId", "variantId", "imageIds", "message");
        assertThat(result.get("variantId")).isEqualTo("VAR-001");
        assertThat(result.get("imageIds")).asList().isEmpty();

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper, times(1)).insert(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getStatus()).isEqualTo("active");
        assertThat(rspuCaptor.getValue().getReviewStatus()).isEqualTo("待复核");
        assertThat(rspuCaptor.getValue().getCategoryCode()).isEqualTo("FS");
        assertThat(rspuCaptor.getValue().getPositioningLabel()).isEqualTo("MC");
        assertThat(rspuCaptor.getValue().getProductLevel()).isEqualTo("A");

        verify(rspuCodeService, times(1)).assignCode(anyString(), eq("FS"), eq("MC"), isNull());
        verify(rspuVariantService, times(1)).createVariant(anyString(), any());
        verify(auditLogService, times(1)).logCreate(eq("rspu_master"), anyString(), any(), any());
    }

    @Test
    void createManualEntry_withImages_shouldStoreImages() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString())).thenReturn("images/IMG-MANUAL.jpg");
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-002");
        when(rspuVariantService.createVariant(anyString(), any())).thenReturn(variantResponse);

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        Map<String, Object> result = productService.createManualEntry(request, List.of(image));

        assertThat(result.get("imageIds")).asList().hasSize(1);
        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper, times(1)).insert(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getPrimary()).isTrue();
        assertThat(imageCaptor.getValue().getImageType()).isEqualTo("white_bg");
        assertThat(imageCaptor.getValue().getVariantId()).isEqualTo("VAR-002");
    }

    @Test
    void createManualEntry_shouldRejectInvalidCategory() {
        when(dictService.listByType("category")).thenReturn(categoryDicts());

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("XX");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        assertThatThrownBy(() -> productService.createManualEntry(request, null))
            .isInstanceOf(BusinessException.class);
        verify(rspuMapper, never()).insert(any(RspuMaster.class));
    }

    @Test
    void detectRegionsInImage_shouldReturnProductsFromFirstPage() throws Exception {
        com.rsdp.dto.DocumentProductRegion.PageProduct product = new com.rsdp.dto.DocumentProductRegion.PageProduct();
        product.setEstimatedCategory("BD");
        com.rsdp.dto.DocumentProductRegion page = new com.rsdp.dto.DocumentProductRegion();
        page.setPageType("product");
        page.setProducts(List.of(product));
        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(page));

        List<com.rsdp.dto.DocumentProductRegion.PageProduct> regions =
            productService.detectRegionsInImage("fake-image".getBytes());

        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).getEstimatedCategory()).isEqualTo("BD");
    }

    @Test
    void detectRegionsInImage_shouldReturnEmptyWhenNoProducts() {
        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of());

        assertThat(productService.detectRegionsInImage("fake-image".getBytes())).isEmpty();
    }

    @Test
    void createEntriesFromRegions_shouldCropAndCreateEntryPerRegion() throws Exception {
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(), anyString(), anyLong(), anyString())).thenReturn("images/IMG-REGION.jpg");

        byte[] png = createPngBytes(400, 200);
        com.rsdp.dto.request.RegionEntryRequest.RegionSelection bed =
            new com.rsdp.dto.request.RegionEntryRequest.RegionSelection(
                new com.rsdp.dto.ProductBoundingBox(0.0, 0.0, 0.5, 1.0), "DT", "实木床", "2000*1800*900mm");
        com.rsdp.dto.request.RegionEntryRequest.RegionSelection bench =
            new com.rsdp.dto.request.RegionEntryRequest.RegionSelection(
                new com.rsdp.dto.ProductBoundingBox(0.5, 0.0, 0.5, 1.0), "FS", "长凳", null);

        List<Map<String, Object>> results = productService.createEntriesFromRegions(png, List.of(bed, bench));

        // 每个区域独立建档：2 个 RSPU、2 张主图（含内容哈希）、2 个异步任务
        assertThat(results).hasSize(2);
        verify(rspuMapper, times(2)).insert(any(RspuMaster.class));
        ArgumentCaptor<ImageAssets> imageCaptor = ArgumentCaptor.forClass(ImageAssets.class);
        verify(imageAssetsMapper, times(2)).insert(imageCaptor.capture());
        assertThat(imageCaptor.getAllValues()).allMatch(a -> a.getContentHash() != null && !a.getContentHash().isBlank());
        // 两图区域不同 → 裁剪结果哈希不同
        assertThat(imageCaptor.getAllValues().get(0).getContentHash())
            .isNotEqualTo(imageCaptor.getAllValues().get(1).getContentHash());
        verify(asyncTaskMapper, times(2)).insert(any(AsyncTask.class));
        verify(asyncTaskProcessor, times(2))
            .processProductEntry(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createEntriesFromRegions_shouldRejectEmptyRegions() throws Exception {
        byte[] png = createPngBytes(100, 100);
        assertThatThrownBy(() -> productService.createEntriesFromRegions(png, List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("至少选择一个产品区域");
    }

    private byte[] createPngBytes(int width, int height) {
        try (var out = new java.io.ByteArrayOutputStream()) {
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            // 左半红、右半蓝，保证两个区域裁剪结果不同
            g.setColor(java.awt.Color.RED);
            g.fillRect(0, 0, width / 2, height);
            g.setColor(java.awt.Color.BLUE);
            g.fillRect(width / 2, 0, width - width / 2, height);
            g.dispose();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
