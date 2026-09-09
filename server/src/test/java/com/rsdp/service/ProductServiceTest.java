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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
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
    private RskuService rskuService;

    @Mock
    private UserFactoryService userFactoryService;

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
        setField("transactionManager", syncTransactionManager());
    }

    /** 同步事务桩：TransactionTemplate 直接在当前线程执行，不产生真实事务。 */
    private static PlatformTransactionManager syncTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // 无真实事务，直接成功
            }

            @Override
            public void rollback(TransactionStatus status) {
                // 无真实事务，直接成功
            }
        };
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWithRoles(String username, String... roles) {
        SecurityContextHolder.clearContext();
        var user = User.withUsername(username).password("").roles(roles).build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private ImageAssets duplicateImageAsset() {
        ImageAssets dup = new ImageAssets();
        dup.setImageId("IMG-DUP");
        dup.setRspuId("RSPU-DUP");
        return dup;
    }

    private RspuMaster duplicateRspu() {
        RspuMaster dupRspu = new RspuMaster();
        dupRspu.setRspuId("RSPU-DUP");
        dupRspu.setProductName("扶摇沙发");
        dupRspu.setRspuCode("FS-WJ-001-M");
        return dupRspu;
    }

    @Test
    void createEntry_shouldRejectDuplicateImageByContentHash() throws Exception {
        // 平台员工（ADMIN）视角：查重报错保留品名 + 业务编码定位信息
        authenticateWithRoles("admin", "ADMIN");
        MockMultipartFile image = new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        // 库内已有同内容图片（对应已有产品）
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(duplicateImageAsset());
        when(rspuMapper.selectById("RSPU-DUP")).thenReturn(duplicateRspu());

        assertThatThrownBy(() -> productService.createEntry(List.of(image), null))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("已录入过")
            .hasMessageContaining("扶摇沙发")
            .hasMessageContaining("FS-WJ-001-M");
        verify(rspuMapper, org.mockito.Mockito.never()).insert(any(RspuMaster.class));
    }

    @Test
    void createEntry_duplicate_forDesigner_shouldMaskDuplicateProductInfo() throws Exception {
        // 非平台员工（DESIGNER）视角：查重报错脱敏，不含已有产品的品名/编码（跨数据归属防泄露）
        authenticateWithRoles("designer", "DESIGNER");
        MockMultipartFile image = new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(duplicateImageAsset());

        assertThatThrownBy(() -> productService.createEntry(List.of(image), null))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("已录入过系统")
            .hasMessageContaining("仍然导入")
            .hasMessageNotContaining("扶摇沙发")
            .hasMessageNotContaining("FS-WJ-001-M")
            .hasMessageNotContaining("RSPU-DUP");
        // 脱敏分支不查询已有产品主档
        verify(rspuMapper, never()).selectById(anyString());
        verify(rspuMapper, never()).insert(any(RspuMaster.class));
    }

    @Test
    void createEntry_duplicate_forFactoryAdmin_shouldMaskDuplicateProductInfo() throws Exception {
        // 非平台员工（FACTORY_ADMIN）视角：查重报错同样脱敏
        authenticateWithRoles("factory", "FACTORY_ADMIN");
        MockMultipartFile image = new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(duplicateImageAsset());

        assertThatThrownBy(() -> productService.createEntry(List.of(image), null))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("已录入过系统")
            .hasMessageNotContaining("扶摇沙发")
            .hasMessageNotContaining("FS-WJ-001-M");
        verify(rspuMapper, never()).selectById(anyString());
        verify(rspuMapper, never()).insert(any(RspuMaster.class));
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
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);

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

        verify(rspuCodeService, times(1)).tryAssignCode(anyString(), eq("FS"), eq("MC"), isNull());
        // 录入场景必须走 createVariantForEntry（跳过数据权限校验），禁止走 createVariant
        verify(rspuVariantService, times(1)).createVariantForEntry(anyString(), any());
        verify(rspuVariantService, never()).createVariant(anyString(), any());
        verify(auditLogService, times(1)).logCreate(eq("rspu_master"), anyString(), any(), any());
    }

    @Test
    void createManualEntry_withImages_shouldStoreImages() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString())).thenReturn("images/IMG-MANUAL.jpg");
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-002");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);

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
    void createManualEntry_withoutSizeCode_shouldSucceedAndReturnNullRspuCode() throws Exception {
        // 不传 sizeCode：tryAssignCode 容错返回 null，录入照常成功、响应含 rspuCode=null
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(rspuCodeService.tryAssignCode(anyString(), anyString(), anyString(), isNull())).thenReturn(null);
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-003");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        Map<String, Object> result = productService.createManualEntry(request, null);

        assertThat(result).containsKey("rspuCode");
        assertThat(result.get("rspuCode")).isNull();
        assertThat(result.get("message")).isEqualTo("手工录入产品成功");
        verify(rspuMapper, times(1)).insert(any(RspuMaster.class));
    }

    @Test
    void createManualEntry_withSizeCode_shouldReturnRspuCode() throws Exception {
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(rspuCodeService.tryAssignCode(anyString(), eq("FS"), eq("MC"), eq("M"))).thenReturn("FS-MC-001-M");
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-004");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");
        request.setSizeCode("m");

        Map<String, Object> result = productService.createManualEntry(request, null);

        assertThat(result.get("rspuCode")).isEqualTo("FS-MC-001-M");
    }

    @Test
    void createFactoryEntry_withoutSizeCode_shouldSucceedAndReturnNullRspuCode() throws Exception {
        // 工厂录入不传 sizeCode：录入照常成功、响应含 rspuCode=null
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(userFactoryService.getFactoryCodesByUsername(anyString())).thenReturn(List.of("A004"));
        when(rspuCodeService.tryAssignCode(anyString(), anyString(), anyString(), isNull())).thenReturn(null);
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-F02");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);
        when(rskuService.createRsku(any())).thenReturn("RSKU-F02");

        com.rsdp.dto.request.FactoryProductEntryRequest request = new com.rsdp.dto.request.FactoryProductEntryRequest();
        request.setFactoryCode("A004");
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("工厂标准版");
        request.setVariantMaterialCode("WO");
        request.setFactoryPrice(new java.math.BigDecimal("999.00"));

        Map<String, Object> result = productService.createFactoryEntry(request, null);

        assertThat(result).containsKey("rspuCode");
        assertThat(result.get("rspuCode")).isNull();
        assertThat(result.get("message")).isEqualTo("工厂产品录入成功");
        verify(rspuMapper, times(1)).insert(any(RspuMaster.class));
        verify(rskuService, times(1)).createRsku(any());
    }

    @Test
    void createFactoryEntry_shouldUseCreateVariantForEntry() throws Exception {
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(userFactoryService.getFactoryCodesByUsername(anyString())).thenReturn(List.of("A004"));
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-F01");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);
        when(rskuService.createRsku(any())).thenReturn("RSKU-F01");

        com.rsdp.dto.request.FactoryProductEntryRequest request = new com.rsdp.dto.request.FactoryProductEntryRequest();
        request.setFactoryCode("A004");
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("工厂标准版");
        request.setVariantMaterialCode("WO");
        request.setFactoryPrice(new java.math.BigDecimal("1234.56"));

        Map<String, Object> result = productService.createFactoryEntry(request, null);

        assertThat(result).containsKeys("rspuId", "variantId", "rskuId", "imageIds", "message");
        assertThat(result.get("variantId")).isEqualTo("VAR-F01");
        assertThat(result.get("rskuId")).isEqualTo("RSKU-F01");

        // 录入场景必须走 createVariantForEntry（跳过数据权限校验），禁止走 createVariant
        verify(rspuVariantService, times(1)).createVariantForEntry(anyString(), any());
        verify(rspuVariantService, never()).createVariant(anyString(), any());
        verify(rspuMapper, times(1)).insert(any(RspuMaster.class));
        verify(rskuService, times(1)).createRsku(any());
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
