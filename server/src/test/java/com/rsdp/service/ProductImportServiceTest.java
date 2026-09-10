package com.rsdp.service;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.excel.ProductImportRow;
import com.rsdp.dto.response.ProductImportResult;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ContentHashes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductImportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductImportServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuStyleMapper rspuStyleMapper;

    @Mock
    private RspuSceneMapper rspuSceneMapper;

    @Mock
    private RspuVariantMapper rspuVariantMapper;

    @Mock
    private VariantCodeMapper variantCodeMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private DictService dictService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StorageService storageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private DictAliasService dictAliasService;

    @Mock
    private DictUnresolvedService dictUnresolvedService;

    @Mock
    private RspuCodeService rspuCodeService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @InjectMocks
    private ProductImportService productImportService;

    private final List<RspuMaster> insertedRspus = new ArrayList<>();
    private final List<RspuVariant> insertedVariants = new ArrayList<>();
    private final List<RspuStyle> insertedStyles = new ArrayList<>();
    private final List<RspuScene> insertedScenes = new ArrayList<>();
    private final List<ImageAssets> insertedImages = new ArrayList<>();

    private HttpServer imageServer;

    /** 图片服务器 /chair.jpg 返回的固定字节（真实 JPEG 魔数），供 content_hash 断言复用 */
    private static final byte[] CHAIR_JPG_BYTES =
        {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46};

    @BeforeEach
    void setUp() throws Exception {
        insertedRspus.clear();
        insertedVariants.clear();
        insertedStyles.clear();
        insertedScenes.clear();
        insertedImages.clear();

        // 绕过真实事务
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // 注入 ObjectMapper
        java.lang.reflect.Field field = ProductImportService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(productImportService, objectMapper);

        // 测试环境允许访问本地图片服务
        productImportService.setAllowedImageHosts(Set.of("127.0.0.1", "localhost"));

        // 跳过真实 RSPU 业务编码生成
        when(rspuCodeService.assignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-001-M");

        // 跳过数据权限校验
        when(dataScopeHelper.canAccessRspu(anyString())).thenReturn(true);

        // 预加载字典
        when(dictService.listByType("category")).thenReturn(List.of(createDict("FS", "座椅")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("MC", "中古风")));
        when(dictService.listByType("scene")).thenReturn(List.of(createDict("LIVING", "客厅")));
        when(dictService.listByType("material")).thenReturn(List.of(createDict("PE", "PE仿藤")));
        when(dictService.listByType("size")).thenReturn(List.of(createDict("M", "中号")));
        when(dictService.listByType("color")).thenReturn(List.of(createDict("BROWN", "棕色")));
        when(dictService.listByType("factory_level")).thenReturn(List.of(createDict("S", "S级")));

        // 捕获插入实体，避免 BaseMapper insert 重载导致 verify 歧义
        doAnswer(invocation -> {
            insertedRspus.add(invocation.getArgument(0));
            return 1;
        }).when(rspuMapper).insert(any(RspuMaster.class));

        doAnswer(invocation -> {
            insertedVariants.add(invocation.getArgument(0));
            return 1;
        }).when(rspuVariantMapper).insert(any(RspuVariant.class));

        doAnswer(invocation -> {
            insertedStyles.add(invocation.getArgument(0));
            return 1;
        }).when(rspuStyleMapper).insert(any(RspuStyle.class));

        doAnswer(invocation -> {
            insertedScenes.add(invocation.getArgument(0));
            return 1;
        }).when(rspuSceneMapper).insert(any(RspuScene.class));

        doAnswer(invocation -> {
            insertedImages.add(invocation.getArgument(0));
            return 1;
        }).when(imageAssetsMapper).insert(any(ImageAssets.class));
    }

    @AfterEach
    void tearDown() {
        if (imageServer != null) {
            imageServer.stop(0);
        }
    }

    @Test
    void importProducts_shouldCreateRspuWithoutVariantAndImage() {
        ProductImportRow row = createValidRow();
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);

        assertThat(insertedRspus).hasSize(1);
        RspuMaster saved = insertedRspus.get(0);
        assertThat(saved.getCategoryCode()).isEqualTo("FS");
        assertThat(saved.getPositioningLabel()).isEqualTo("MC");
        assertThat(saved.getExternalCode()).isEqualTo("EXT-001");

        verify(rspuStyleMapper).delete(any());
        assertThat(insertedStyles).hasSize(1);
        assertThat(insertedStyles.get(0).getStyleCode()).isEqualTo("MC");

        verify(rspuSceneMapper).delete(any());
        assertThat(insertedScenes).hasSize(1);
        assertThat(insertedScenes.get(0).getSceneCode()).isEqualTo("LIVING");
    }

    @Test
    void importProducts_unknownVariantCode_shouldDowngradeToText() {
        // V19：尺寸码未识别不再整行失败——码置空、原文保留到 sizeText，行导入成功并采集待治理
        ProductImportRow row = createValidRow();
        row.setVariantDisplayName("贵妃位版本");
        row.setSizeCode("贵妃A位");
        row.setColorCode(null);
        row.setMaterialCode(null);
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(rspuVariantMapper.selectList(any())).thenReturn(List.of());
        when(variantCodeMapper.allocateSequence(anyString())).thenReturn(1L);

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(insertedVariants).hasSize(1);
        assertThat(insertedVariants.get(0).getSizeCode()).isNull();
        assertThat(insertedVariants.get(0).getSizeText()).isEqualTo("贵妃A位");
        verify(dictUnresolvedService).record(
            org.mockito.ArgumentMatchers.eq("size"),
            org.mockito.ArgumentMatchers.eq("贵妃A位"),
            org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void importProducts_shouldPersistDescription() {
        ProductImportRow row = createValidRow();
        row.setDescription("材质解析：橡木框架\n填充：高密度海绵");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(insertedRspus).hasSize(1);
        assertThat(insertedRspus.get(0).getDescription()).isEqualTo("材质解析：橡木框架\n填充：高密度海绵");
    }

    @Test
    void importProducts_shouldCreateRspuWithVariant() {
        ProductImportRow row = createValidRow();
        row.setVariantDisplayName("标准版");
        row.setSizeCode("M");
        row.setColorCode("BROWN");
        row.setMaterialCode("PE");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(rspuVariantMapper.selectList(any())).thenReturn(List.of());
        when(variantCodeMapper.allocateSequence(anyString())).thenReturn(1L);

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(insertedVariants).hasSize(1);
        assertThat(insertedVariants.get(0).getVariantId()).startsWith("RSPU-");
        assertThat(insertedVariants.get(0).getSizeCode()).isEqualTo("M");
    }

    @Test
    void importProducts_shouldUpdateByExternalCode() {
        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        MockMultipartFile file = createExcelFile(List.of(row));

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-OLD001");
        existing.setExternalCode("EXT-001");
        existing.setCategoryCode("FS");
        existing.setCategoryPath("[\"家具\"]");
        existing.setPositioningLabel("MC");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        ProductImportResult result = productImportService.importProducts(file, true);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(insertedRspus).isEmpty();
        verify(rspuMapper).updateById(any(RspuMaster.class));
    }

    @Test
    void importProducts_updateModeAuditSnapshot_shouldContainProductNameDescriptionRetailPriceFabricTags() {
        // P2-6：审计旧快照必须包含 buildRspuFromRow 更新模式会改的
        // productName / description / retailPrice / fabricTags 四个字段
        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setProductName("新品名");
        row.setDescription("新描述");
        row.setRetailPrice(new java.math.BigDecimal("2999.00"));
        MockMultipartFile file = createExcelFile(List.of(row));

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-OLD001");
        existing.setExternalCode("EXT-001");
        existing.setCategoryCode("FS");
        existing.setCategoryPath("[\"家具\"]");
        existing.setPositioningLabel("MC");
        existing.setProductName("旧品名");
        existing.setDescription("旧描述");
        existing.setRetailPrice(new java.math.BigDecimal("1999.00"));
        existing.setFabricTags("[\"LINEN\"]");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        ProductImportResult result = productImportService.importProducts(file, true);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        ArgumentCaptor<Object> oldCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditLogService).logUpdate(org.mockito.ArgumentMatchers.eq("rspu_master"),
            org.mockito.ArgumentMatchers.eq("RSPU-OLD001"), oldCaptor.capture(), any(), any());
        RspuMaster oldSnapshot = (RspuMaster) oldCaptor.getValue();
        assertThat(oldSnapshot.getProductName()).isEqualTo("旧品名");
        assertThat(oldSnapshot.getDescription()).isEqualTo("旧描述");
        assertThat(oldSnapshot.getRetailPrice()).isEqualByComparingTo(new java.math.BigDecimal("1999.00"));
        assertThat(oldSnapshot.getFabricTags()).isEqualTo("[\"LINEN\"]");
    }

    @Test
    void importProducts_shouldSkipWhenExistsAndUpdateIfExistsFalse() {
        ProductImportRow row = createValidRow();
        MockMultipartFile file = createExcelFile(List.of(row));

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-OLD001");
        existing.setExternalCode("EXT-001");
        when(rspuMapper.selectById("RSPU-OLD001")).thenReturn(existing);

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("已跳过");
    }

    @Test
    void importProducts_shouldDetectDuplicateExternalCodeInExcel() {
        ProductImportRow row1 = createValidRow();
        ProductImportRow row2 = createValidRow();
        MockMultipartFile file = createExcelFile(List.of(row1, row2));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("重复");
    }

    @Test
    void importProducts_shouldDetectDuplicateWhenSameProductReferencedByRspuIdAndExternalCode() {
        // 同一产品在 Excel 中分别用 rspuId（第 1 行）和 externalCode（第 2 行）引用
        ProductImportRow row1 = createValidRow();
        row1.setExternalCode(null);
        ProductImportRow row2 = createValidRow();
        row2.setRspuId(null);
        MockMultipartFile file = createExcelFile(List.of(row1, row2));

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-OLD001");
        existing.setExternalCode("EXT-001");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));
        when(rspuMapper.selectById("RSPU-OLD001")).thenReturn(existing);

        ProductImportResult result = productImportService.importProducts(file, false);

        // 第 1 行按「产品已存在」跳过，第 2 行识别为重复行
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(2);
        assertThat(result.getFailures().get(1).getReason()).contains("重复");
    }

    @Test
    void importProducts_shouldFailWhenCategoryCodeInvalid() {
        ProductImportRow row = createValidRow();
        row.setCategoryCode("XX");
        MockMultipartFile file = createExcelFile(List.of(row));

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("品类码不存在");
    }

    @Test
    void importProducts_gradeCodePositioningLabel_shouldSucceedAndAssignCode() {
        // 2.8：办公家具职级码存独立 grade 字典，定位标签填职级码（EX）应导入成功并正常发号
        when(dictService.listByType("grade")).thenReturn(List.of(createDict("EX", "总裁级")));
        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setPositioningLabel("EX");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(insertedRspus).hasSize(1);
        assertThat(insertedRspus.get(0).getPositioningLabel()).isEqualTo("EX");
        // 命中 grade 字典即视为有效定位标签，正常生成 RSPU 业务编码
        verify(rspuCodeService).assignCode(anyString(), org.mockito.ArgumentMatchers.eq("FS"),
            org.mockito.ArgumentMatchers.eq("EX"), anyString());
        // 职级码不写 rspu_style（与手工/AI 录入链路口径一致，避免污染风格筛选）
        assertThat(insertedStyles).isEmpty();
    }

    @Test
    void importProducts_unknownPositioningLabel_shouldStillFail() {
        // 2.8：style 与 grade 字典均未命中的定位标签仍被拒
        when(dictService.listByType("grade")).thenReturn(List.of(createDict("EX", "总裁级")));
        ProductImportRow row = createValidRow();
        row.setPositioningLabel("NOT-A-STYLE");
        MockMultipartFile file = createExcelFile(List.of(row));

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("定位标签不存在");
    }

    @Test
    void importProducts_shouldSucceedEvenWhenImageDownloadFails() {
        ProductImportRow row = createValidRow();
        // 使用白名单内的本地地址 + 未监听端口，连接即刻被拒绝，快速触发下载失败分支
        row.setPrimaryImageUrl("http://127.0.0.1:1/test.jpg");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("主图下载失败");
        assertThat(insertedImages).isEmpty();
    }

    @Test
    void importProducts_shouldRejectPrivateImageUrl() throws Exception {
        // 移除本地白名单，模拟生产环境
        productImportService.setAllowedImageHosts(Set.of());

        java.lang.reflect.Field hostsField = ProductImportService.class.getDeclaredField("allowedImageHosts");
        hostsField.setAccessible(true);

        ProductImportRow row = createValidRow();
        row.setPrimaryImageUrl("http://127.0.0.1:1/test.jpg");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("不安全的图片 URL");
        assertThat(insertedImages).isEmpty();
    }

    @Test
    void importProducts_shouldSucceedWithDownloadedImage() throws IOException {
        startImageServer();

        ProductImportRow row = createValidRow();
        row.setPrimaryImageUrl("http://127.0.0.1:" + imageServer.getAddress().getPort() + "/chair.jpg");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString()))
            .thenReturn("images/IMG-TEST01.jpg");

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(insertedImages).hasSize(1);
        assertThat(insertedImages.get(0).getImageType()).isEqualTo("white_bg");
        // 2.5：导入图片写入 content_hash（下载字节的 SHA-256）
        assertThat(insertedImages.get(0).getContentHash()).isEqualTo(ContentHashes.sha256Hex(CHAIR_JPG_BYTES));
    }

    @Test
    void importProducts_reimportSameImage_shouldSkipDuplicateRegistration() throws IOException {
        // 2.5：更新模式重导同一模板，同 RSPU 同内容图片跳过重登记，不再累积图片副本
        startImageServer();

        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setPrimaryImageUrl("http://127.0.0.1:" + imageServer.getAddress().getPort() + "/chair.jpg");
        MockMultipartFile file = createExcelFile(List.of(row));

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-OLD001");
        existing.setExternalCode("EXT-001");
        existing.setCategoryCode("FS");
        existing.setCategoryPath("[\"家具\"]");
        existing.setPositioningLabel("MC");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));
        // 库中已有同内容主图（hash 与本次下载字节一致）
        ImageAssets existingImage = new ImageAssets();
        existingImage.setImageId("IMG-OLD02");
        existingImage.setRspuId("RSPU-OLD001");
        existingImage.setPrimary(true);
        existingImage.setContentHash(ContentHashes.sha256Hex(CHAIR_JPG_BYTES));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(existingImage));

        ProductImportResult result = productImportService.importProducts(file, true);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        // 同 hash 跳过重登记：不登记新图片资产，也不重复写存储文件
        assertThat(insertedImages).isEmpty();
        verify(storageService, never()).store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString());
    }

    @Test
    void importProducts_shouldHandleDatabaseUniqueConstraintViolation() {
        ProductImportRow row = createValidRow();
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
        }).when(rspuMapper).insert(any(RspuMaster.class));

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        // 2.7：唯一冲突保持「重复导入」口径
        assertThat(result.getFailures().get(0).getReason()).contains("已存在").contains("重复导入");
    }

    @Test
    void importProducts_unknownSceneTag_shouldSkipInsertCollectAndWarn() {
        // 2.7：场景标签未归一 → 不插 rspu_scene（防复合 FK 违例行回滚）+ 采集 dict_unresolved
        // + 行级警告，行建档成功；命中值正常写入，归一后同码去重
        ProductImportRow row = createValidRow();
        row.setSceneTags("LIVING,太空舱,living");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        // 只插归一命中的 LIVING（living 归一后同码去重），未命中的「太空舱」不插
        assertThat(insertedScenes).hasSize(1);
        assertThat(insertedScenes.get(0).getSceneCode()).isEqualTo("LIVING");
        verify(dictUnresolvedService).record(
            org.mockito.ArgumentMatchers.eq("scene"),
            org.mockito.ArgumentMatchers.eq("太空舱"),
            org.mockito.ArgumentMatchers.isNull(), any());
        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).getReason())
            .contains("场景标签未识别").contains("太空舱");
    }

    @Test
    void importProducts_sceneTagNormalizedByDictName_shouldInsert() {
        // 2.7：场景标签填字典名（中文名）时归一为字典码写入，与定位标签归一口径一致
        ProductImportRow row = createValidRow();
        row.setSceneTags("客厅");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(insertedScenes).hasSize(1);
        assertThat(insertedScenes.get(0).getSceneCode()).isEqualTo("LIVING");
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    void importProducts_foreignKeyViolation_shouldReportReferenceCheckMessage() {
        // 2.7 兜底防线：残留的外键违例（非唯一冲突）报「数据引用校验失败」并附约束名，
        // 不再被误译为「重复导入」
        ProductImportRow row = createValidRow();
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException(
                "insert or update on table \"rspu_scene\" violates foreign key constraint \"rspu_scene_dict_fk\"");
        }).when(rspuMapper).insert(any(RspuMaster.class));

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason())
            .contains("数据引用校验失败")
            .contains("rspu_scene_dict_fk")
            .doesNotContain("重复导入");
    }

    @Test
    void importProducts_shouldRecordFailureWhenImageStorageFails() throws IOException {
        startImageServer();

        ProductImportRow row = createValidRow();
        row.setPrimaryImageUrl("http://127.0.0.1:" + imageServer.getAddress().getPort() + "/chair.jpg");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString()))
            .thenThrow(new IOException("disk full"));

        ProductImportResult result = productImportService.importProducts(file, false);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("图片存储失败");
        assertThat(insertedImages).isEmpty();
    }

    @Test
    void importProducts_shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        assertThatThrownBy(() -> productImportService.importProducts(file, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("上传文件不能为空");
    }

    @Test
    void importProducts_shouldRejectOversizedFile() {
        byte[] largeContent = new byte[(int) (11 * 1024 * 1024)];
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", largeContent);

        assertThatThrownBy(() -> productImportService.importProducts(file, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("文件大小不能超过");
    }

    @Test
    void importProducts_updateModeWithBlankCells_shouldPreserveExistingFields() {
        // Given：已有产品各字段齐全，更新行仅显式提供品类码与外部编码，其余留空
        ProductImportRow row = new ProductImportRow();
        row.setExternalCode("EXT-001");
        row.setCategoryCode("FS");
        MockMultipartFile file = createExcelFile(List.of(row));

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-OLD001");
        existing.setExternalCode("EXT-001");
        existing.setCategoryCode("FS");
        existing.setCategoryPath("[\"家具\"]");
        existing.setPositioningLabel("MC");
        existing.setColorPrimaryName("棕色");
        existing.setMaterialTags("[\"PE\"]");
        existing.setSceneTags("[\"LIVING\"]");
        existing.setSixDimTags("{\"A\":\"A字架形\"}");
        existing.setReferencePriceBand("mid");
        existing.setProductLevel("S");
        existing.setWarrantyYears(3);
        existing.setKeySpecs("{\"frame\":\"oak\"}");
        existing.setProductName("旧品名");
        existing.setDescription("旧描述\n第二行");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));

        // When
        ProductImportResult result = productImportService.importProducts(file, true);

        // Then：空单元格不覆盖已有字段
        assertThat(result.getSuccessCount()).isEqualTo(1);
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).updateById(captor.capture());
        RspuMaster saved = captor.getValue();
        assertThat(saved.getPositioningLabel()).isEqualTo("MC");
        assertThat(saved.getColorPrimaryName()).isEqualTo("棕色");
        assertThat(saved.getMaterialTags()).isEqualTo("[\"PE\"]");
        assertThat(saved.getSceneTags()).isEqualTo("[\"LIVING\"]");
        assertThat(saved.getSixDimTags()).isEqualTo("{\"A\":\"A字架形\"}");
        assertThat(saved.getReferencePriceBand()).isEqualTo("mid");
        assertThat(saved.getProductLevel()).isEqualTo("S");
        assertThat(saved.getWarrantyYears()).isEqualTo(3);
        assertThat(saved.getKeySpecs()).isEqualTo("{\"frame\":\"oak\"}");
        assertThat(saved.getProductName()).isEqualTo("旧品名");
        assertThat(saved.getDescription()).isEqualTo("旧描述\n第二行");
        // 行内定位标签/场景标签留空时跳过风格与场景关联的 delete+重建
        verify(rspuStyleMapper, never()).delete(any());
        verify(rspuSceneMapper, never()).delete(any());
    }

    @Test
    void importProducts_shouldNormalizeChineseDictNamesBeforeValidation() {
        // Given：字典字段填中文名/字典名称，应先归一为字典码再校验
        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setExternalCode("EXT-NEW01");
        row.setPositioningLabel("中古风");
        row.setProductLevel("S级");
        row.setVariantDisplayName("标准版");
        row.setSizeCode("中号");
        row.setColorCode("棕色");
        row.setMaterialCode("PE仿藤");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(rspuVariantMapper.selectList(any())).thenReturn(List.of());
        when(variantCodeMapper.allocateSequence(anyString())).thenReturn(1L);

        // When
        ProductImportResult result = productImportService.importProducts(file, false);

        // Then：中文名正常通过校验并归一为字典码
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(insertedRspus).hasSize(1);
        assertThat(insertedRspus.get(0).getPositioningLabel()).isEqualTo("MC");
        assertThat(insertedRspus.get(0).getProductLevel()).isEqualTo("S");
        assertThat(insertedVariants).hasSize(1);
        assertThat(insertedVariants.get(0).getSizeCode()).isEqualTo("M");
        assertThat(insertedVariants.get(0).getColorCode()).isEqualTo("BROWN");
        assertThat(insertedVariants.get(0).getMaterialCode()).isEqualTo("PE");
    }

    @Test
    void importProducts_variantLookup_shouldDeduplicateByEffectiveValues() {
        // Given：变体仅提供尺寸码，颜色/材质留空（有效值均为空串语义）
        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setExternalCode("EXT-NEW02");
        row.setVariantDisplayName("标准版");
        row.setSizeCode("M");
        row.setColorCode(null);
        row.setMaterialCode(null);
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(rspuVariantMapper.selectList(any())).thenReturn(List.of());
        when(variantCodeMapper.allocateSequence(anyString())).thenReturn(1L);

        // When
        ProductImportResult result = productImportService.importProducts(file, false);

        // Then：按 rspu_id 查出变体后在应用层按"码或原文"有效值判重（不再拼接 IS NULL 条件）
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(insertedVariants).hasSize(1);
        assertThat(insertedVariants.get(0).getSizeCode()).isEqualTo("M");
        assertThat(insertedVariants.get(0).getColorCode()).isNull();
        assertThat(insertedVariants.get(0).getMaterialCode()).isNull();
    }

    @Test
    void importProducts_variantUniqueViolation_shouldReportDimensionConflictMessage() {
        // Given：查重未命中但插入时撞 uk_variant_attrs（并发导入同维度变体）
        ProductImportRow row = createValidRow();
        row.setVariantDisplayName("标准版");
        row.setSizeCode("M");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(rspuVariantMapper.selectList(any())).thenReturn(List.of());
        when(variantCodeMapper.allocateSequence(anyString())).thenReturn(1L);
        doAnswer(invocation -> {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_variant_attrs\"");
        }).when(rspuVariantMapper).insert(any(RspuVariant.class));

        // When
        ProductImportResult result = productImportService.importProducts(file, false);

        // Then：报变体维度冲突文案，而非笼统的「编码已存在」
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("相同尺寸/颜色/材质的变体已存在");
    }

    @Test
    void importProducts_shouldFailWhenRspuIdAndExternalCodePointToDifferentProducts() {
        // Given：行内 rspuId 与 externalCode 分别指向不同产品
        ProductImportRow row = createValidRow();
        row.setRspuId("RSPU-A");
        MockMultipartFile file = createExcelFile(List.of(row));

        RspuMaster byRspuId = new RspuMaster();
        byRspuId.setRspuId("RSPU-A");
        when(rspuMapper.selectById("RSPU-A")).thenReturn(byRspuId);

        RspuMaster byExternalCode = new RspuMaster();
        byExternalCode.setRspuId("RSPU-OLD001");
        byExternalCode.setExternalCode("EXT-001");
        when(rspuMapper.selectList(any())).thenReturn(List.of(byExternalCode));

        // When
        ProductImportResult result = productImportService.importProducts(file, true);

        // Then：显式报行错误，不静默选其一
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("指向不同产品");
        verify(rspuMapper, never()).updateById(any(RspuMaster.class));
    }

    @Test
    void importProducts_shouldRejectSvgImage() throws IOException {
        // Given：Content-Type 声明为 image/svg+xml 的 SVG 内容
        startImageServer();

        ProductImportRow row = createValidRow();
        row.setPrimaryImageUrl("http://127.0.0.1:" + imageServer.getAddress().getPort() + "/logo.svg");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());

        // When
        ProductImportResult result = productImportService.importProducts(file, false);

        // Then：SVG 被内容嗅探拒绝，产品数据仍正常导入
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("主图下载失败");
        assertThat(insertedImages).isEmpty();
    }

    @Test
    void importProducts_shouldSniffImageFormatOverContentType() throws IOException {
        // Given：PNG 魔数但 Content-Type 伪装为 text/plain
        startImageServer();

        ProductImportRow row = createValidRow();
        row.setPrimaryImageUrl("http://127.0.0.1:" + imageServer.getAddress().getPort() + "/photo.png");
        MockMultipartFile file = createExcelFile(List.of(row));

        when(rspuMapper.selectList(any())).thenReturn(List.of());
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString()))
            .thenReturn("images/IMG-TEST02.png");

        // When
        ProductImportResult result = productImportService.importProducts(file, false);

        // Then：以嗅探结果为准识别为 png
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(insertedImages).hasSize(1);
        assertThat(insertedImages.get(0).getFormat()).isEqualTo("png");
        assertThat(insertedImages.get(0).getStoragePath()).endsWith(".png");
    }

    @Test
    void importProducts_shouldRejectImageWithOversizedContentLength() throws IOException {
        // Content-Length 预检：声明超限长度直接拒绝，不读取正文（防恶意 URL 超大响应撑爆堆内存）
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/big.jpg", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                // 声明 25MB Content-Length 但不发送正文：预检应直接拒绝
                exchange.sendResponseHeaders(200, 25L * 1024 * 1024);
                exchange.close();
            }
        });
        server.start();
        try {
            ProductImportRow row = createValidRow();
            row.setPrimaryImageUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/big.jpg");
            MockMultipartFile file = createExcelFile(List.of(row));

            when(rspuMapper.selectList(any())).thenReturn(List.of());

            ProductImportResult result = productImportService.importProducts(file, false);

            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getFailedCount()).isEqualTo(1);
            assertThat(result.getFailures().get(0).getReason()).contains("主图下载失败");
            assertThat(insertedImages).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void importProducts_shouldRejectImageWhenBodyExceedsSizeLimit() throws IOException {
        // 限量读取兜底：Content-Length 缺失/谎报（chunked）时，readNBytes 读到超过上限即拒绝
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/huge.jpg", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                // responseLength=0：chunked 传输，不带 Content-Length，绕过预检
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[20 * 1024 * 1024 + 1]);
                exchange.close();
            }
        });
        server.start();
        try {
            ProductImportRow row = createValidRow();
            row.setPrimaryImageUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/huge.jpg");
            MockMultipartFile file = createExcelFile(List.of(row));

            when(rspuMapper.selectList(any())).thenReturn(List.of());

            ProductImportResult result = productImportService.importProducts(file, false);

            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getFailedCount()).isEqualTo(1);
            assertThat(result.getFailures().get(0).getReason()).contains("主图下载失败");
            assertThat(insertedImages).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void importProducts_updateMode_shouldNotCreateSecondPrimaryImage() throws IOException {
        // Given：更新模式追加图片，该 RSPU 已存在主图
        startImageServer();

        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setPrimaryImageUrl("http://127.0.0.1:" + imageServer.getAddress().getPort() + "/chair.jpg");
        MockMultipartFile file = createExcelFile(List.of(row));

        RspuMaster existing = new RspuMaster();
        existing.setRspuId("RSPU-OLD001");
        existing.setExternalCode("EXT-001");
        existing.setCategoryCode("FS");
        existing.setCategoryPath("[\"家具\"]");
        existing.setPositioningLabel("MC");
        when(rspuMapper.selectList(any())).thenReturn(List.of(existing));
        // 已有主图（2.5 起由 saveImages 按 RSPU 预取 selectList 派生，不再走 selectCount）
        ImageAssets existingPrimary = new ImageAssets();
        existingPrimary.setImageId("IMG-OLD01");
        existingPrimary.setRspuId("RSPU-OLD001");
        existingPrimary.setPrimary(true);
        existingPrimary.setContentHash("other-content-hash");
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(existingPrimary));
        when(storageService.store(any(ByteArrayInputStream.class), anyString(), anyLong(), anyString()))
            .thenReturn("images/IMG-TEST03.jpg");

        // When
        ProductImportResult result = productImportService.importProducts(file, true);

        // Then：已有主图时新图一律不作为主图
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(insertedImages).hasSize(1);
        assertThat(insertedImages.get(0).getPrimary()).isFalse();
        assertThat(insertedImages.get(0).getImageType()).isEqualTo("detail");
    }

    @Test
    void importProducts_shouldRejectOverlongVariantDisplayName() {
        // Given：变体显示名称超过 rspu_variant.display_name 列宽 128
        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setExternalCode("EXT-NEW03");
        row.setVariantDisplayName("a".repeat(129));
        MockMultipartFile file = createExcelFile(List.of(row));

        // When
        ProductImportResult result = productImportService.importProducts(file, false);

        // Then：行级长度校验报错，不穿透到数据库约束异常
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("变体显示名称 长度不能超过 128");
    }

    @Test
    void importProducts_shouldRejectOverlongColorPrimaryName() {
        // Given：主色超过 color_primary_name 列宽 64
        ProductImportRow row = createValidRow();
        row.setRspuId(null);
        row.setExternalCode("EXT-NEW04");
        row.setColorPrimaryName("b".repeat(65));
        MockMultipartFile file = createExcelFile(List.of(row));

        // When
        ProductImportResult result = productImportService.importProducts(file, false);

        // Then
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("主色 长度不能超过 64");
    }

    private void startImageServer() throws IOException {
        imageServer = HttpServer.create(new InetSocketAddress(0), 0);
        // 真实 JPEG 魔数（FF D8 FF ...），内容嗅探应识别为 jpg
        imageServer.createContext("/chair.jpg", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = CHAIR_JPG_BYTES;
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        // SVG 内容：即使 Content-Type 声明 image/svg+xml 也应被拒绝（存储型 XSS 面）
        imageServer.createContext("/logo.svg", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();
                exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        // PNG 魔数但 Content-Type 伪装为 text/plain：以嗅探结果为准，识别为 png
        imageServer.createContext("/photo.png", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        imageServer.start();
    }

    private ProductImportRow createValidRow() {
        ProductImportRow row = new ProductImportRow();
        row.setRspuId("RSPU-OLD001");
        row.setExternalCode("EXT-001");
        row.setCategoryCode("FS");
        row.setPositioningLabel("MC");
        row.setColorPrimaryName("棕色");
        row.setMaterialTags("PE");
        row.setSceneTags("LIVING");
        row.setProductLevel("S");
        row.setWarrantyYears(3);
        row.setReferencePriceBand("mid");
        return row;
    }

    private MockMultipartFile createExcelFile(List<ProductImportRow> rows) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        EasyExcel.write(outputStream, ProductImportRow.class).sheet("Sheet1").doWrite(rows);
        byte[] bytes = outputStream.toByteArray();
        return new MockMultipartFile("file", "products.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private CategoryDict createDict(String code, String name) {
        CategoryDict dict = new CategoryDict();
        dict.setDictCode(code);
        dict.setDictName(name);
        return dict;
    }
}
