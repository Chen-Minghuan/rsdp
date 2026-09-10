package com.rsdp.service;

import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.RspuCodeMapper;
import com.rsdp.mapper.RspuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link RspuCodeService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RspuCodeServiceTest {

    @Mock
    private RspuCodeMapper rspuCodeMapper;

    @Mock
    private DictService dictService;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RspuCodeService rspuCodeService;

    private void stubDicts() {
        when(dictService.listByType("category")).thenReturn(List.of(createDict("FS"), createDict("DT")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("MC")));
        when(dictService.listByType("grade")).thenReturn(List.of());
        when(dictService.listByType("size")).thenReturn(List.of(createDict("M"), createDict("L")));
    }

    private CategoryDict createDict(String code) {
        CategoryDict dict = new CategoryDict();
        dict.setDictCode(code);
        return dict;
    }

    @Test
    void generateNextCode_shouldReturnBusinessCode() {
        stubDicts();
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(1L, 2L);

        String code1 = rspuCodeService.generateNextCode("FS", "MC", "M");
        String code2 = rspuCodeService.generateNextCode("FS", "MC", "L");

        assertThat(code1).isEqualTo("FS-MC-001-M");
        assertThat(code2).isEqualTo("FS-MC-002-L");
    }

    @Test
    void generateNextCode_shouldThrow_WhenSizeCodeMissing() {
        assertThatThrownBy(() -> rspuCodeService.generateNextCode("FS", "MC", null))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("尺寸码不能为空");
    }

    @Test
    void generateNextCode_shouldThrow_WhenCategoryCodeMissing() {
        assertThatThrownBy(() -> rspuCodeService.generateNextCode(null, "MC", "M"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("品类码不能为空");
    }

    @Test
    void generateNextCode_shouldThrow_WhenStyleCodeMissing() {
        assertThatThrownBy(() -> rspuCodeService.generateNextCode("FS", null, "M"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("风格码不能为空");
    }

    @Test
    void generateNextCode_shouldThrow_WhenCategoryCodeInvalid() {
        when(dictService.listByType("category")).thenReturn(List.of(createDict("FS"), createDict("DT")));
        assertThatThrownBy(() -> rspuCodeService.generateNextCode("XX", "MC", "M"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("品类码不存在");
    }

    @Test
    void generateNextCode_shouldThrow_WhenStyleCodeInvalid() {
        when(dictService.listByType("category")).thenReturn(List.of(createDict("FS")));
        when(dictService.listByType("style")).thenReturn(List.of());
        when(dictService.listByType("grade")).thenReturn(List.of());
        assertThatThrownBy(() -> rspuCodeService.generateNextCode("FS", "XX", "M"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("风格/职级码不存在");
    }

    @Test
    void generateNextCode_shouldThrow_WhenSizeCodeInvalid() {
        stubDicts();
        assertThatThrownBy(() -> rspuCodeService.generateNextCode("FS", "MC", "XX"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("尺寸码不存在");
    }

    @Test
    void assignCode_shouldReturnExistingCode_whenAlreadyAssigned() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode("FS-MC-001-M");
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);

        String code = rspuCodeService.assignCode("RSPU-001", "FS", "MC", "M");

        assertThat(code).isEqualTo("FS-MC-001-M");
    }

    @Test
    void assignCode_shouldGenerateAndPersist_whenNotAssigned() {
        stubDicts();
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(5L);

        String code = rspuCodeService.assignCode("RSPU-001", "FS", "MC", "M");

        assertThat(code).isEqualTo("FS-MC-005-M");
        assertThat(rspu.getRspuCode()).isEqualTo("FS-MC-005-M");
    }

    @Test
    void assignCode_shouldWriteAuditLogWithOperator() {
        // P1-3：发号改写 rspu_code 记 logUpdate 审计（旧值 null → 新编码），操作人显式传入
        stubDicts();
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(5L);

        String code = rspuCodeService.assignCode("RSPU-001", "FS", "MC", "M", "editor01");

        assertThat(code).isEqualTo("FS-MC-005-M");
        org.mockito.ArgumentCaptor<Object> oldCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.ArgumentCaptor<Object> newCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(auditLogService).logUpdate(
            org.mockito.ArgumentMatchers.eq("rspu_master"), org.mockito.ArgumentMatchers.eq("RSPU-001"),
            oldCaptor.capture(), newCaptor.capture(), org.mockito.ArgumentMatchers.eq("editor01"));
        assertThat(((RspuMaster) oldCaptor.getValue()).getRspuCode()).isNull();
        assertThat(((RspuMaster) newCaptor.getValue()).getRspuCode()).isEqualTo("FS-MC-005-M");
    }

    @Test
    void tryAssignCode_shouldReturnCode_whenAssignSucceeds() {
        stubDicts();
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(3L);

        String code = rspuCodeService.tryAssignCode("RSPU-001", "FS", "MC", "M");

        assertThat(code).isEqualTo("FS-MC-003-M");
        assertThat(rspu.getRspuCode()).isEqualTo("FS-MC-003-M");
    }

    @Test
    void tryAssignCode_shouldReturnNullAndNotThrow_whenAssignCodeThrowsBusinessException() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);

        // 尺寸码为空 → assignCode 抛 BusinessException，tryAssignCode 容错返回 null 且不外抛
        String code = rspuCodeService.tryAssignCode("RSPU-001", "FS", "MC", null);

        assertThat(code).isNull();
        assertThat(rspu.getRspuCode()).isNull();
    }

    @Test
    void tryAssignCode_shouldReturnExistingCode_whenAlreadyAssigned() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode("FS-MC-001-M");
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);

        String code = rspuCodeService.tryAssignCode("RSPU-001", "FS", "MC", "M");

        assertThat(code).isEqualTo("FS-MC-001-M");
    }

    @Test
    void generateNextCode_shouldThrow_WhenSequenceExceedsLimit() {
        stubDicts();
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(1000L);

        assertThatThrownBy(() -> rspuCodeService.generateNextCode("FS", "MC", "M"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("流水号已超过最大值");
    }

    @Test
    void inferSizeCode_shouldDegradeToNearestExistingCode_whenInferredNotInDict() {
        // 字典只有 M/L：AI 推断 X（≥1800mm）应降级为 L，而非让编码生成整体失败
        when(dictService.listByType("size")).thenReturn(List.of(createDict("M"), createDict("L")));
        com.rsdp.dto.AiLabels labels = labelsWithDimensions(2000, 900, 800);

        String sizeCode = rspuCodeService.inferSizeCode(labels);

        assertThat(sizeCode).isEqualTo("L");
    }

    @Test
    void inferSizeCode_shouldReturnInferredCode_whenExistsInDict() {
        when(dictService.listByType("size")).thenReturn(List.of(createDict("M"), createDict("L")));
        com.rsdp.dto.AiLabels labels = labelsWithDimensions(1500, 900, 800);

        String sizeCode = rspuCodeService.inferSizeCode(labels);

        assertThat(sizeCode).isEqualTo("L");
    }

    @Test
    void inferSizeCodeFromMm_shouldInferByThresholds() {
        when(dictService.listByType("size"))
            .thenReturn(List.of(createDict("S"), createDict("M"), createDict("L"), createDict("X")));

        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertThat(rspuCodeService.inferSizeCodeFromMm(500L)).isEqualTo("S"),
            () -> assertThat(rspuCodeService.inferSizeCodeFromMm(1000L)).isEqualTo("M"),
            () -> assertThat(rspuCodeService.inferSizeCodeFromMm(1500L)).isEqualTo("L"),
            () -> assertThat(rspuCodeService.inferSizeCodeFromMm(2200L)).isEqualTo("X"),
            () -> assertThat(rspuCodeService.inferSizeCodeFromMm(null)).isNull(),
            () -> assertThat(rspuCodeService.inferSizeCodeFromMm(0L)).isNull()
        );
    }

    @Test
    void inferSizeCodeFromMm_shouldDegradeToNearestExistingCode() {
        // 字典只有 M/L：推断 X 应降级为 L（与 OCR 路径同一降级语义）
        when(dictService.listByType("size")).thenReturn(List.of(createDict("M"), createDict("L")));

        assertThat(rspuCodeService.inferSizeCodeFromMm(2200L)).isEqualTo("L");
    }

    @Test
    void inferSizeCode_shouldReturnNull_whenNoDegradableCodeInDict() {
        // 字典里只有非等级码（如 SINGLE）：无等级码可降级，返回 null 走"存疑"路径
        when(dictService.listByType("size")).thenReturn(List.of(createDict("SINGLE")));
        com.rsdp.dto.AiLabels labels = labelsWithDimensions(2000, 900, 800);

        String sizeCode = rspuCodeService.inferSizeCode(labels);

        assertThat(sizeCode).isNull();
    }

    private com.rsdp.dto.AiLabels labelsWithDimensions(int w, int d, int h) {
        return labelsWithDimensions(w, d, h, null);
    }

    private com.rsdp.dto.AiLabels labelsWithDimensions(int w, int d, int h, String unit) {
        com.rsdp.dto.Dimensions dims = new com.rsdp.dto.Dimensions();
        dims.setW(w);
        dims.setD(d);
        dims.setH(h);
        dims.setUnit(unit);
        com.rsdp.dto.OcrResult ocr = new com.rsdp.dto.OcrResult();
        ocr.setDimensions(dims);
        com.rsdp.dto.AiLabels labels = new com.rsdp.dto.AiLabels();
        labels.setOcr(ocr);
        return labels;
    }

    @Test
    void inferSizeCode_shouldConvertCmToMmBeforeThreshold() {
        when(dictService.listByType("size")).thenReturn(List.of(createDict("S"), createDict("M"), createDict("L")));
        // 80cm = 800mm → M；不换算会被误判为 S（实测案例：80×38×40cm 电视柜）
        assertThat(rspuCodeService.inferSizeCode(labelsWithDimensions(80, 38, 40, "cm"))).isEqualTo("M");
    }

    @Test
    void inferSizeCode_shouldConvertMeterToMmBeforeThreshold() {
        when(dictService.listByType("size")).thenReturn(List.of(createDict("L"), createDict("X")));
        // 2m = 2000mm → X
        assertThat(rspuCodeService.inferSizeCode(labelsWithDimensions(2, 1, 1, "m"))).isEqualTo("X");
    }
}
