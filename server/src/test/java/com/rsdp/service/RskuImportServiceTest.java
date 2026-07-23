package com.rsdp.service;

import com.rsdp.dto.excel.RskuImportRow;
import com.rsdp.dto.response.RskuImportResult;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.PriceHistoryMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.rsdp.security.datascope.DataScopeHelper;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RskuImportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RskuImportServiceTest {

    @BeforeEach
    void setSecurityContext() {
        var user = User.withUsername("admin").password("").roles("ADMIN").build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        lenient().when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuVariantMapper rspuVariantMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private FactoryService factoryService;

    @Mock
    private DictService dictService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PriceHistoryMapper priceHistoryMapper;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private RskuImportService rskuImportService;

    private List<CategoryDict> factoryLevelDicts() {
        CategoryDict s = new CategoryDict();
        s.setDictType("factory_level");
        s.setDictCode("S");
        s.setDictName("S级");
        return List.of(s);
    }

    private List<CategoryDict> quoteConfidenceDicts() {
        CategoryDict high = new CategoryDict();
        high.setDictType("quote_confidence");
        high.setDictCode("high");
        high.setDictName("高");
        CategoryDict mid = new CategoryDict();
        mid.setDictType("quote_confidence");
        mid.setDictCode("mid");
        mid.setDictName("中");
        CategoryDict low = new CategoryDict();
        low.setDictType("quote_confidence");
        low.setDictCode("low");
        low.setDictName("低");
        return List.of(high, mid, low);
    }

    private String header() {
        return "RSPU编码,工厂编码,变体编码,出厂价,工厂SKU,材质说明,交期（天）,最小起订量,质保年限,发货地,差异备注,报价置信度,产品等级";
    }

    @Test
    void importRskus_shouldSucceedForValidRows() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500,FS-001,实木,30,10,3,广东,无差异,高,S"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);

        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        assertThat(captor.getValue().getFactoryPrice()).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(captor.getValue().getQuoteConfidence()).isEqualTo("high");
        assertThat(captor.getValue().getProductLevel()).isEqualTo("S");
    }

    @Test
    void importRskus_shouldRejectInvalidQuoteConfidence() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500,FS-001,实木,30,10,3,广东,无差异,极高,S"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("报价置信度不存在");
        verify(rskuSupplyMapper, org.mockito.Mockito.never()).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_shouldSkipExistingRowsWhenUpdateIfExistsFalse() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-OLD01");
        existing.setFactoryCode("F001");
        existing.setVariantId("VAR-001");
        existing.setFactoryPrice(new BigDecimal("1000"));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(existing));

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("已有报价");
        verify(rskuSupplyMapper, never()).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_shouldUpdateExistingRowsWhenUpdateIfExistsTrue() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-OLD01");
        existing.setFactoryCode("F001");
        existing.setVariantId("VAR-001");
        existing.setFactoryPrice(new BigDecimal("1000"));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(existing));

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, true);

        // Then
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        verify(rskuSupplyMapper).updateById(any(RskuSupply.class));
        verify(rskuSupplyMapper, never()).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_shouldReportValidationFailures() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of());
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of());
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,-100"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("出厂价");
    }

    @Test
    void importRskus_emptyFile_shouldThrowBusinessException() {
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);
        assertThatThrownBy(() -> rskuImportService.importRskus(file, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("上传文件不能为空");
    }

    @Test
    void importRskus_duplicateRowsInExcel_shouldReportFailure() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500",
            "RSPU-001,F001,VAR-001,2000"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("重复报价行");
        verify(rskuSupplyMapper).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_updateExisting_shouldLogAuditWithOldSnapshot() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-OLD01");
        existing.setFactoryCode("F001");
        existing.setVariantId("VAR-001");
        existing.setFactoryPrice(new BigDecimal("1000"));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(existing));

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        rskuImportService.importRskus(file, true);

        // Then
        ArgumentCaptor<RskuSupply> oldValueCaptor = ArgumentCaptor.forClass(RskuSupply.class);
        ArgumentCaptor<RskuSupply> newValueCaptor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(auditLogService).logUpdate(eq("rsku_supply"), eq("RSKU-OLD01"), oldValueCaptor.capture(), newValueCaptor.capture(), eq("admin"));
        assertThat(oldValueCaptor.getValue().getFactoryPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(newValueCaptor.getValue().getFactoryPrice()).isEqualByComparingTo(new BigDecimal("1500"));

        ArgumentCaptor<PriceHistory> historyCaptor = ArgumentCaptor.forClass(PriceHistory.class);
        verify(priceHistoryMapper).insert(historyCaptor.capture());
        PriceHistory history = historyCaptor.getValue();
        assertThat(history.getRskuId()).isEqualTo("RSKU-OLD01");
        assertThat(history.getOldPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(history.getNewPrice()).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(history.getChangeReason()).isEqualTo("批量导入更新");
    }

    @Test
    void importRskus_unsupportedContentType_shouldThrowBusinessException() {
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> rskuImportService.importRskus(file, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅支持 Excel");
    }

    @Test
    void importRskus_chineseProductLevelAndQuoteConfidence_shouldNormalizeToCode() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500,FS-001,实木,30,10,3,广东,无差异,中,S级"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getSuccessCount()).isEqualTo(1);
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        RskuSupply saved = captor.getValue();
        assertThat(saved.getQuoteConfidence()).isEqualTo("mid");
        assertThat(saved.getProductLevel()).isEqualTo("S");
    }

    @Test
    void importRskus_boundaryValidation_shouldReportFailures() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of());
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of());
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,100000000,FS-001,,1000,1000000,60,广东,,invalid-confidence,S"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getFailedCount()).isEqualTo(1);
        String reason = result.getFailures().get(0).getReason();
        assertThat(reason).contains("出厂价");
    }

    @Test
    void importRskus_shouldProcessRemainingRowsWhenOneRowConflicts() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");

        RspuVariant variant2 = new RspuVariant();
        variant2.setVariantId("VAR-002");
        variant2.setRspuId("RSPU-001");
        variant2.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant, variant2));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());
        when(rskuSupplyMapper.insert(any(RskuSupply.class)))
            .thenReturn(1)
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500",
            "RSPU-001,F001,VAR-002,2000"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then
        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        verify(rskuSupplyMapper, times(2)).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_shouldExcludeDeletedRskuWhenPreloadingExisting() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        rskuImportService.importRskus(file, false);

        // Then
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RskuSupply>> wrapperCaptor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(rskuSupplyMapper).selectList(wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getCustomSqlSegment();
        assertThat(sqlSegment).containsIgnoringCase("deleted_at IS NULL");
    }

    @Test
    void updateSingleRsku_emptyCells_shouldNotOverwriteExistingFieldsNorRecordPriceHistory() {
        // Given：已有报价各字段齐全，导入行仅显式给出材质说明，价格等单元格为空
        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-OLD01");
        existing.setRspuId("RSPU-001");
        existing.setVariantId("VAR-001");
        existing.setFactoryCode("F001");
        existing.setFactorySku("FS-OLD");
        existing.setFactoryPrice(new BigDecimal("1000"));
        existing.setPriceBand("mid");
        existing.setLeadTimeDays(30);
        existing.setMoq(10);
        existing.setWarrantyYears(3);

        RskuImportRow row = new RskuImportRow();
        row.setRspuId("RSPU-001");
        row.setVariantId("VAR-001");
        row.setFactoryCode("F001");
        row.setFactoryPrice(null);
        row.setMaterialDescription("橡木");

        // When
        rskuImportService.updateSingleRsku(existing, row, "S");

        // Then：空单元格不覆盖已有字段，显式有值字段正常更新
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).updateById(captor.capture());
        RskuSupply saved = captor.getValue();
        assertThat(saved.getFactoryPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(saved.getPriceBand()).isEqualTo("mid");
        assertThat(saved.getFactorySku()).isEqualTo("FS-OLD");
        assertThat(saved.getLeadTimeDays()).isEqualTo(30);
        assertThat(saved.getMoq()).isEqualTo(10);
        assertThat(saved.getWarrantyYears()).isEqualTo(3);
        assertThat(saved.getMaterialDescription()).isEqualTo("橡木");
        // 价格未被清空，不写 old→null 的价格历史
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
        verify(auditLogService).logUpdate(eq("rsku_supply"), eq("RSKU-OLD01"), any(), any(), eq("admin"));
    }

    @Test
    void importRskus_unexpectedRowException_shouldRecordFailureAndContinue() throws Exception {
        // Given：第 1 行插入时抛出未预期运行时异常，第 2 行正常
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");

        RspuVariant variant2 = new RspuVariant();
        variant2.setVariantId("VAR-002");
        variant2.setRspuId("RSPU-001");
        variant2.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant, variant2));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());
        when(rskuSupplyMapper.insert(any(RskuSupply.class)))
            .thenThrow(new IllegalStateException("boom"))
            .thenReturn(1);

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500",
            "RSPU-001,F001,VAR-002,2000"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then：单行未预期异常不中断整批，记失败明细后继续
        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("系统异常");
        verify(rskuSupplyMapper, times(2)).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_shouldTrimCodesWhenPreloading() throws Exception {
        // Given：Excel 中编码带前后空格，preload 收集与 map 查找都应使用 trim 后的编码
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            " RSPU-001 , F001 , VAR-001 ,1500"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then：导入成功（trim 后能命中预加载 map），且预加载查询使用 trim 后的编码
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rspuIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(rspuMapper).selectBatchIds(rspuIdsCaptor.capture());
        assertThat(rspuIdsCaptor.getValue()).containsExactly("RSPU-001");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> factoryCodesCaptor = ArgumentCaptor.forClass(List.class);
        verify(factoryMasterMapper).selectBatchIds(factoryCodesCaptor.capture());
        assertThat(factoryCodesCaptor.getValue()).containsExactly("F001");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> variantIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(rspuVariantMapper).selectBatchIds(variantIdsCaptor.capture());
        assertThat(variantIdsCaptor.getValue()).containsExactly("VAR-001");
    }

    @Test
    void importRskus_updateModeWithEmptyPrice_shouldKeepExistingPrice() throws Exception {
        // Given：更新模式且已有报价，导入行价格留空（行内只有前 3 列）
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-OLD01");
        existing.setFactoryCode("F001");
        existing.setVariantId("VAR-001");
        existing.setFactoryPrice(new BigDecimal("1000"));
        existing.setPriceBand("low");
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(existing));

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, true);

        // Then：更新模式价格留空放行，已有价格与价格带不被覆盖，不记价格历史
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getFactoryPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(captor.getValue().getPriceBand()).isEqualTo("low");
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
    }

    @Test
    void importRskus_insertModeWithEmptyPrice_shouldReject() throws Exception {
        // Given：即使开启 updateIfExists，无已有报价的行仍按插入处理，价格必填
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, true);

        // Then
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("出厂价");
        verify(rskuSupplyMapper, never()).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_ambiguousDictNamePrefix_shouldNotNormalize() throws Exception {
        // Given：两个字典名称共享前缀，前缀输入无法唯一归一时保留原值并校验报错
        CategoryDict std = new CategoryDict();
        std.setDictType("quote_confidence");
        std.setDictCode("std");
        std.setDictName("标准级");
        CategoryDict prm = new CategoryDict();
        prm.setDictType("quote_confidence");
        prm.setDictCode("prm");
        prm.setDictName("标准定制级");
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(List.of(std, prm));
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500,FS-001,实木,30,10,3,广东,无差异,标准,S"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then：「标准」同时是两个字典名的前缀，不予归一，按无效字典值报错
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getReason()).contains("报价置信度不存在");
        verify(rskuSupplyMapper, never()).insert(any(RskuSupply.class));
    }

    @Test
    void importRskus_uniqueDictNamePrefix_shouldNormalize() throws Exception {
        // Given：前缀只命中唯一字典名时正常归一
        CategoryDict std = new CategoryDict();
        std.setDictType("quote_confidence");
        std.setDictCode("std");
        std.setDictName("标准级");
        CategoryDict prm = new CategoryDict();
        prm.setDictType("quote_confidence");
        prm.setDictCode("prm");
        prm.setDictName("标准定制级");
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(List.of(std, prm));
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S")));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setProductLevel("S");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-001");
        variant.setRspuId("RSPU-001");
        variant.setProductLevel("S");
        when(rspuVariantMapper.selectBatchIds(any())).thenReturn(List.of(variant));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        byte[] excelBytes = buildExcelWithRows(List.of(
            header(),
            "RSPU-001,F001,VAR-001,1500,FS-001,实木,30,10,3,广东,无差异,标准定,S"
        ));
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        // When
        RskuImportResult result = rskuImportService.importRskus(file, false);

        // Then：「标准定」唯一前缀命中「标准定制级」→ 归一为 prm
        assertThat(result.getSuccessCount()).isEqualTo(1);
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        assertThat(captor.getValue().getQuoteConfidence()).isEqualTo("prm");
    }

    private byte[] buildExcelWithRows(List<String> csvRows) throws Exception {
        // 使用 CSV 格式生成简单测试数据，EasyExcel 也能读取 CSV
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String row : csvRows) {
            out.write(row.getBytes());
            out.write('\n');
        }
        return out.toByteArray();
    }
}
