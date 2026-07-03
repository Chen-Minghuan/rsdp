package com.rsdp.service;

import com.rsdp.dto.response.RskuImportResult;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RskuImportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RskuImportServiceTest {

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
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));

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

        ArgumentCaptor<List<RskuSupply>> captor = ArgumentCaptor.forClass(List.class);
        verify(rskuSupplyMapper).insertBatchSafe(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getFactoryPrice()).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(captor.getValue().get(0).getQuoteConfidence()).isEqualTo("high");
        assertThat(captor.getValue().get(0).getProductLevel()).isEqualTo("S");
    }

    @Test
    void importRskus_shouldSkipExistingRowsWhenUpdateIfExistsFalse() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));

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
        verify(rskuSupplyMapper, never()).insertBatchSafe(any());
    }

    @Test
    void importRskus_shouldUpdateExistingRowsWhenUpdateIfExistsTrue() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));

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
        verify(rskuSupplyMapper, never()).insertBatchSafe(any());
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
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));

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
        verify(rskuSupplyMapper).insertBatchSafe(anyList());
    }

    @Test
    void importRskus_updateExisting_shouldLogAuditWithOldSnapshot() throws Exception {
        // Given
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        when(dictService.listByType("quote_confidence")).thenReturn(quoteConfidenceDicts());
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));

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
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));

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
        ArgumentCaptor<List<RskuSupply>> captor = ArgumentCaptor.forClass(List.class);
        verify(rskuSupplyMapper).insertBatchSafe(captor.capture());
        RskuSupply saved = captor.getValue().get(0);
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
