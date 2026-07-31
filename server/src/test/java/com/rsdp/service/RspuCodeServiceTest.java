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
    void generateNextCode_shouldThrow_WhenSequenceExceedsLimit() {
        stubDicts();
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(1000L);

        assertThatThrownBy(() -> rspuCodeService.generateNextCode("FS", "MC", "M"))
            .isInstanceOf(com.rsdp.exception.BusinessException.class)
            .hasMessageContaining("流水号已超过最大值");
    }
}
