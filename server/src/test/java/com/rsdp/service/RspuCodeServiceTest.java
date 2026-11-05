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
 *
 * <p>编码规则（2026-10-14 起）：{品类}-{风格}-{流水4位}；风格缺失/未归一降级 TBD；
 * 编码不再含尺寸段。</p>
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
        org.mockito.Mockito.lenient().when(dictService.listByType("style")).thenReturn(List.of(createDict("MC"), createDict("TBD")));
        org.mockito.Mockito.lenient().when(dictService.listByType("grade")).thenReturn(List.of());
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

        String code1 = rspuCodeService.generateNextCode("FS", "MC");
        String code2 = rspuCodeService.generateNextCode("FS", "MC");

        assertThat(code1).isEqualTo("FS-MC-0001");
        assertThat(code2).isEqualTo("FS-MC-0002");
    }

    @Test
    void generateNextCode_shouldThrow_WhenCategoryCodeMissing() {
        assertThatThrownBy(() -> rspuCodeService.generateNextCode(null, "MC"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("品类码不能为空");
    }

    @Test
    void generateNextCode_shouldThrow_WhenCategoryCodeInvalid() {
        when(dictService.listByType("category")).thenReturn(List.of(createDict("FS"), createDict("DT")));
        assertThatThrownBy(() -> rspuCodeService.generateNextCode("XX", "MC"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("品类码不存在");
    }

    @Test
    void generateNextCode_shouldFallbackToTbd_WhenStyleMissing() {
        // 风格缺失不阻断发号：风格段降级为保留段 TBD
        stubDicts();
        when(rspuCodeMapper.allocateSequence("FS", "TBD")).thenReturn(7L);

        String code = rspuCodeService.generateNextCode("FS", null);

        assertThat(code).isEqualTo("FS-TBD-0007");
    }

    @Test
    void generateNextCode_shouldFallbackToTbd_WhenStyleNotInDict() {
        // 风格未归一（字典外原文）同样降级 TBD，不再抛异常
        stubDicts();
        when(rspuCodeMapper.allocateSequence("FS", "TBD")).thenReturn(8L);

        String code = rspuCodeService.generateNextCode("FS", "北欧风");

        assertThat(code).isEqualTo("FS-TBD-0008");
    }

    @Test
    void generateNextCode_shouldAcceptGradeCode() {
        when(dictService.listByType("category")).thenReturn(List.of(createDict("FS")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("TBD")));
        when(dictService.listByType("grade")).thenReturn(List.of(createDict("EX")));
        when(rspuCodeMapper.allocateSequence("FS", "EX")).thenReturn(1L);

        String code = rspuCodeService.generateNextCode("FS", "EX");

        assertThat(code).isEqualTo("FS-EX-0001");
    }

    @Test
    void assignCode_shouldReturnExistingCode_whenAlreadyAssigned() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode("FS-MC-0001");
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);

        String code = rspuCodeService.assignCode("RSPU-001", "FS", "MC");

        assertThat(code).isEqualTo("FS-MC-0001");
    }

    @Test
    void assignCode_shouldGenerateAndPersist_whenNotAssigned() {
        stubDicts();
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(5L);
        when(rspuMapper.assignCodeIfAbsent(anyString(), anyString())).thenReturn(1);

        String code = rspuCodeService.assignCode("RSPU-001", "FS", "MC");

        assertThat(code).isEqualTo("FS-MC-0005");
        assertThat(rspu.getRspuCode()).isEqualTo("FS-MC-0005");
    }

    @Test
    void assignCode_shouldReturnConcurrentCode_whenCondUpdateHitsZeroRows() {
        // 并发双发：条件 UPDATE 影响 0 行说明编码已被并发发放，
        // 回读返回现有编码（幂等安全），不覆盖、不重复记审计
        stubDicts();
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        RspuMaster concurrent = new RspuMaster();
        concurrent.setRspuId("RSPU-001");
        concurrent.setRspuCode("FS-MC-0009");
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu, concurrent);
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(5L);
        when(rspuMapper.assignCodeIfAbsent(anyString(), anyString())).thenReturn(0);

        String code = rspuCodeService.assignCode("RSPU-001", "FS", "MC");

        assertThat(code).isEqualTo("FS-MC-0009");
        org.mockito.Mockito.verify(auditLogService, org.mockito.Mockito.never()).logUpdate(
            anyString(), anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyString());
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
        when(rspuMapper.assignCodeIfAbsent(anyString(), anyString())).thenReturn(1);

        String code = rspuCodeService.assignCode("RSPU-001", "FS", "MC", "editor01");

        assertThat(code).isEqualTo("FS-MC-0005");
        org.mockito.ArgumentCaptor<Object> oldCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.ArgumentCaptor<Object> newCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(auditLogService).logUpdate(
            org.mockito.ArgumentMatchers.eq("rspu_master"), org.mockito.ArgumentMatchers.eq("RSPU-001"),
            oldCaptor.capture(), newCaptor.capture(), org.mockito.ArgumentMatchers.eq("editor01"));
        assertThat(((RspuMaster) oldCaptor.getValue()).getRspuCode()).isNull();
        assertThat(((RspuMaster) newCaptor.getValue()).getRspuCode()).isEqualTo("FS-MC-0005");
    }

    @Test
    void tryAssignCode_shouldReturnCode_whenAssignSucceeds() {
        stubDicts();
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(3L);
        when(rspuMapper.assignCodeIfAbsent(anyString(), anyString())).thenReturn(1);

        String code = rspuCodeService.tryAssignCode("RSPU-001", "FS", "MC");

        assertThat(code).isEqualTo("FS-MC-0003");
        assertThat(rspu.getRspuCode()).isEqualTo("FS-MC-0003");
    }

    @Test
    void tryAssignCode_shouldReturnNullAndNotThrow_whenAssignCodeThrowsBusinessException() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode(null);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);

        // 品类码为空 → assignCode 抛 BusinessException，tryAssignCode 容错返回 null 且不外抛
        String code = rspuCodeService.tryAssignCode("RSPU-001", null, "MC");

        assertThat(code).isNull();
        assertThat(rspu.getRspuCode()).isNull();
    }

    @Test
    void tryAssignCode_shouldReturnExistingCode_whenAlreadyAssigned() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setRspuCode("FS-MC-0001");
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);

        String code = rspuCodeService.tryAssignCode("RSPU-001", "FS", "MC");

        assertThat(code).isEqualTo("FS-MC-0001");
    }

    @Test
    void generateNextCode_shouldThrow_WhenSequenceExceedsLimit() {
        stubDicts();
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(10000L);

        assertThatThrownBy(() -> rspuCodeService.generateNextCode("FS", "MC"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("流水号已超过最大值");
    }
}
