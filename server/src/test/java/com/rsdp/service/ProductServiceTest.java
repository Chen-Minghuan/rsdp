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
import com.rsdp.util.ContentHashes;
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

    @Mock
    private com.rsdp.security.datascope.DataScopeHelper dataScopeHelper;

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
        // 2.5：手工/工厂录入图片写入 content_hash（图片字节的 SHA-256）
        assertThat(imageCaptor.getValue().getContentHash())
            .isEqualTo(ContentHashes.sha256Hex("fake-image".getBytes()));
        // 主图裁剪不在录入事务内同步调用 AI（无活动事务时直接走异步入口）
        verify(subjectCropService, never()).cropAndReplacePrimary(any(), any(), any(), any(), any());
        verify(subjectCropService, times(1)).cropAndReplacePrimaryAsync(any(), any(), any(), any(), any());
    }

    @Test
    void createManualEntry_duplicateImage_shouldReject() throws Exception {
        // 2.5 查重拦截（平台员工 ADMIN 视角）：同 hash 图片已在库时拒绝，文案带产品定位信息
        authenticateWithRoles("admin", "ADMIN");
        MockMultipartFile image = new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-DUP");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(duplicateImageAsset());
        when(rspuMapper.selectById("RSPU-DUP")).thenReturn(duplicateRspu());

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        assertThatThrownBy(() -> productService.createManualEntry(request, List.of(image)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已录入过")
            .hasMessageContaining("扶摇沙发")
            .hasMessageContaining("FS-WJ-001-M")
            // 手工/工厂录入无 force 参数，文案不含「仍然导入」指引
            .hasMessageContaining("暂不支持强制跳过")
            .hasMessageNotContaining("仍然导入");
        // 拦截在图片存储与登记之前（RSPU/变体在同事务内会随异常回滚）
        verify(storageService, never()).store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString());
        verify(imageAssetsMapper, never()).insert(any(ImageAssets.class));
    }

    @Test
    void createFactoryEntry_duplicateImage_forFactoryAdmin_shouldMaskDuplicateInfo() throws Exception {
        // 2.5 查重拦截（非平台员工 FACTORY_ADMIN 视角）：报错脱敏，不含已有产品品名/编码
        authenticateWithRoles("factory", "FACTORY_ADMIN");
        MockMultipartFile image = new MockMultipartFile(
            "image", "sofa.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(userFactoryService.getFactoryCodesByUsername(anyString())).thenReturn(List.of("A004"));
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-FDUP");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(duplicateImageAsset());

        com.rsdp.dto.request.FactoryProductEntryRequest request = new com.rsdp.dto.request.FactoryProductEntryRequest();
        request.setFactoryCode("A004");
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("工厂标准版");
        request.setVariantMaterialCode("WO");
        request.setFactoryPrice(new java.math.BigDecimal("999.00"));

        assertThatThrownBy(() -> productService.createFactoryEntry(request, List.of(image)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已录入过系统")
            .hasMessageContaining("请勿重复录入")
            .hasMessageNotContaining("扶摇沙发")
            .hasMessageNotContaining("FS-WJ-001-M")
            .hasMessageNotContaining("RSPU-DUP");
        // 脱敏分支不查询已有产品主档
        verify(rspuMapper, never()).selectById(anyString());
        verify(storageService, never()).store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString());
        verify(imageAssetsMapper, never()).insert(any(ImageAssets.class));
    }

    @Test
    void createManualEntry_sameImageTwiceInRequest_shouldRegisterOnce() throws Exception {
        // 2.5 边界：同一请求内多图相同（用户重复选同一张图）——保留首次出现的图，静默跳过重登记，不拦截整单
        MockMultipartFile first = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        MockMultipartFile sameAgain = new MockMultipartFile(
            "image", "chair-copy.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString())).thenReturn("images/IMG-MANUAL.jpg");
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-005");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        Map<String, Object> result = productService.createManualEntry(request, List.of(first, sameAgain));

        // 只登记一张（首图为主图），重复图不触发查重也不入库
        assertThat(result.get("imageIds")).asList().hasSize(1);
        verify(imageAssetsMapper, times(1)).insert(any(ImageAssets.class));
        verify(storageService, times(1)).store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString());
        verify(imageAssetsMapper, times(1)).selectByContentHash(anyString());
    }

    @Test
    void createManualEntry_withImages_shouldDispatchCropAfterCommit() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString())).thenReturn("images/IMG-MANUAL.jpg");
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-003");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            Map<String, Object> result = productService.createManualEntry(request, List.of(image));
            String imageId = ((List<String>) result.get("imageIds")).get(0);

            // 事务提交前：不同步调裁剪，也不投递异步任务
            verify(subjectCropService, never()).cropAndReplacePrimary(any(), any(), any(), any(), any());
            verify(subjectCropService, never()).cropAndReplacePrimaryAsync(any(), any(), any(), any(), any());

            // 模拟事务提交：afterCommit 回调投递异步裁剪任务
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            for (TransactionSynchronization sync : syncs) {
                sync.afterCommit();
            }

            verify(subjectCropService, times(1)).cropAndReplacePrimaryAsync(
                any(byte[].class), anyString(), eq("VAR-003"), eq(imageId), eq("images/IMG-MANUAL.jpg"));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void createManualEntry_withImages_shouldNotDispatchCropWhenRolledBack() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        when(dictService.listByType("category")).thenReturn(categoryDicts());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString())).thenReturn("images/IMG-MANUAL.jpg");
        com.rsdp.dto.response.RspuVariantResponse variantResponse = new com.rsdp.dto.response.RspuVariantResponse();
        variantResponse.setVariantId("VAR-004");
        when(rspuVariantService.createVariantForEntry(anyString(), any())).thenReturn(variantResponse);

        com.rsdp.dto.request.ManualProductEntryRequest request = new com.rsdp.dto.request.ManualProductEntryRequest();
        request.setCategoryCode("FS");
        request.setPositioningLabel("MC");
        request.setProductLevel("A");
        request.setVariantDisplayName("标准版");
        request.setVariantMaterialCode("WO");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            productService.createManualEntry(request, List.of(image));

            // 模拟事务回滚：afterCompletion(STATUS_ROLLED_BACK) 不触发 afterCommit，裁剪不会执行
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            for (TransactionSynchronization sync : syncs) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            verify(subjectCropService, never()).cropAndReplacePrimary(any(), any(), any(), any(), any());
            verify(subjectCropService, never()).cropAndReplacePrimaryAsync(any(), any(), any(), any(), any());
            // 回滚清理仍覆盖原始图键
            verify(storageService).delete("images/IMG-MANUAL.jpg");
        } finally {
            TransactionSynchronizationManager.clear();
        }
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

    @Test
    void reRecognize_shouldResetRspuAndCreateTask() {
        // 存疑产品重新识别：RSPU 置回 processing + 待复核（清掉存疑备注）、新建 product_entry 任务并投递
        authenticateWithRoles("admin", "ADMIN");
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setStatus("active");
        rspu.setReviewStatus("存疑");
        rspu.setReviewComment("识别任务超时未执行，可重新识别");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);

        ImageAssets primary = new ImageAssets();
        primary.setImageId("IMG-1");
        primary.setRspuId("RSPU-1");
        primary.setPrimary(true);
        primary.setStoragePath("images/IMG-1.jpg");
        when(imageAssetsMapper.selectOne(any())).thenReturn(primary);
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = productService.reRecognize("RSPU-1");

        assertThat(result).containsKeys("taskId", "message");

        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).updateById(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getStatus()).isEqualTo("processing");
        assertThat(rspuCaptor.getValue().getReviewStatus()).isEqualTo("待复核");
        assertThat(rspuCaptor.getValue().getReviewComment()).isNull();

        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(taskCaptor.capture());
        AsyncTask task = taskCaptor.getValue();
        assertThat(task.getTaskType()).isEqualTo("product_entry");
        assertThat(task.getStatus()).isEqualTo("pending");
        assertThat(task.getInputData()).contains("\"rspuId\":\"RSPU-1\"")
            .contains("\"imageId\":\"IMG-1\"");

        verify(dataScopeHelper).assertCanAccessRspu("RSPU-1");
        verify(auditLogService).logReview(eq("rspu_master"), eq("RSPU-1"), any(), any(), anyString());
        verify(asyncTaskProcessor).processProductEntry(anyString(), eq("RSPU-1"), eq("IMG-1"), eq("images/IMG-1.jpg"));
    }

    @Test
    void reRecognize_shouldRejectWhenNoPrimaryImage() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setStatus("processing");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);
        when(imageAssetsMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> productService.reRecognize("RSPU-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("产品没有主图，无法重新识别");
        verify(asyncTaskMapper, never()).insert(any(AsyncTask.class));
        verify(rspuMapper, never()).updateById(any(RspuMaster.class));
    }

    @Test
    void reRecognize_shouldRejectWhenInflightTaskExists() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setStatus("processing");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);

        ImageAssets primary = new ImageAssets();
        primary.setImageId("IMG-1");
        primary.setRspuId("RSPU-1");
        primary.setPrimary(true);
        primary.setStoragePath("images/IMG-1.jpg");
        when(imageAssetsMapper.selectOne(any())).thenReturn(primary);

        AsyncTask inflight = new AsyncTask();
        inflight.setTaskId("TASK-RUNNING");
        inflight.setTaskType("product_entry");
        inflight.setStatus("processing");
        inflight.setInputData("{\"rspuId\":\"RSPU-1\",\"imageId\":\"IMG-1\"}");
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(inflight));

        assertThatThrownBy(() -> productService.reRecognize("RSPU-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已有识别任务在执行中");
        verify(asyncTaskMapper, never()).insert(any(AsyncTask.class));
        verify(rspuMapper, never()).updateById(any(RspuMaster.class));
    }

    @Test
    void reRecognize_shouldRejectWhenFactoryCannotAccessRspu() {
        // 非本厂已报价产品：数据归属校验拒绝
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);
        doThrow(new BusinessException("只能维护本厂已报价的产品: RSPU-1"))
            .when(dataScopeHelper).assertCanAccessRspu("RSPU-1");

        assertThatThrownBy(() -> productService.reRecognize("RSPU-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能维护本厂已报价的产品");
        verify(asyncTaskMapper, never()).insert(any(AsyncTask.class));
    }

    @Test
    void reRecognize_shouldRejectWhenRspuNotFound() {
        when(rspuMapper.selectById("RSPU-X")).thenReturn(null);

        assertThatThrownBy(() -> productService.reRecognize("RSPU-X"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("产品不存在");
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
