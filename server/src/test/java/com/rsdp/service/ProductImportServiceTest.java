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
import com.rsdp.service.storage.StorageService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @InjectMocks
    private ProductImportService productImportService;

    private final List<RspuMaster> insertedRspus = new ArrayList<>();
    private final List<RspuVariant> insertedVariants = new ArrayList<>();
    private final List<RspuStyle> insertedStyles = new ArrayList<>();
    private final List<RspuScene> insertedScenes = new ArrayList<>();
    private final List<ImageAssets> insertedImages = new ArrayList<>();

    private HttpServer imageServer;

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
    void importProducts_shouldSucceedEvenWhenImageDownloadFails() {
        ProductImportRow row = createValidRow();
        row.setPrimaryImageUrl("http://192.0.2.1:1/test.jpg");
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
        assertThat(result.getFailures().get(0).getReason()).contains("已存在");
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

    private void startImageServer() throws IOException {
        imageServer = HttpServer.create(new InetSocketAddress(0), 0);
        imageServer.createContext("/chair.jpg", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = new byte[]{0x01, 0x02, 0x03, 0x04};
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
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
