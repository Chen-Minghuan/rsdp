package com.rsdp.service;

import com.rsdp.mapper.RspuCodeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private RspuCodeService rspuCodeService;

    @Test
    void generateNextCode_shouldReturnBusinessCode() {
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(1L, 2L);

        String code1 = rspuCodeService.generateNextCode("FS", "MC", "M");
        String code2 = rspuCodeService.generateNextCode("FS", "MC", "L");

        assertThat(code1).isEqualTo("FS-MC-001-M");
        assertThat(code2).isEqualTo("FS-MC-002-L");
    }

    @Test
    void generateNextCode_shouldUseX_WhenSizeCodeMissing() {
        when(rspuCodeMapper.allocateSequence(anyString(), anyString())).thenReturn(7L);

        String code = rspuCodeService.generateNextCode("DT", "MC", null);

        assertThat(code).isEqualTo("DT-MC-007-X");
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
}
