package com.rsdp.service;

import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RskuCodeMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RskuCodeService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RskuCodeServiceTest {

    @Mock
    private RskuCodeMapper rskuCodeMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @InjectMocks
    private RskuCodeService rskuCodeService;

    private RspuMaster rspuWithCode() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
        return rspu;
    }

    private RskuSupply rskuWithoutCode(String rskuId, String materialCode) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId(rskuId);
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("A004");
        rsku.setMaterialCode(materialCode);
        return rsku;
    }

    @Test
    void tryAssignCode_shouldReturnNullWhenRspuHasNoCode() {
        // 所属 RSPU 无 rspu_code（如 Excel AI 导入风格缺失暂不发号）：容错返回 null 而不是抛异常
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rskuWithoutCode("RSKU-TEST01", "PE"));
        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);

        String code = rskuCodeService.tryAssignCode("RSKU-TEST01", "RSPU-TEST01", "A004", "PE");

        assertThat(code).isNull();
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void tryAssignCode_shouldAssignWhenRspuHasCode() {
        // RSPU 有码：正常发号并落库
        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rskuWithoutCode("RSKU-TEST01", "PE"));
        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspuWithCode());
        when(rskuCodeMapper.allocateSequence("FS-MC-001-M", "A004", "PE")).thenReturn(1L);

        String code = rskuCodeService.tryAssignCode("RSKU-TEST01", "RSPU-TEST01", "A004", "PE");

        assertThat(code).isEqualTo("FS-MC-001-M-A004-PE-001");
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRskuCode()).isEqualTo("FS-MC-001-M-A004-PE-001");
    }

    @Test
    void assignCode_shouldThrowWhenRspuHasNoCode() {
        // assignCode 抛异常语义保持不变
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);

        assertThatThrownBy(() -> rskuCodeService.assignCode("RSKU-TEST01", "RSPU-TEST01", "A004", "PE"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未生成业务编码");
    }

    @Test
    void assignCode_shouldReturnExistingCodeDirectly() {
        // 已有 rsku_code 直接返回，幂等不重复发号
        RskuSupply rsku = rskuWithoutCode("RSKU-TEST01", "PE");
        rsku.setRskuCode("FS-MC-001-M-A004-PE-003");
        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);

        String code = rskuCodeService.assignCode("RSKU-TEST01", "RSPU-TEST01", "A004", "PE");

        assertThat(code).isEqualTo("FS-MC-001-M-A004-PE-003");
        verify(rspuMapper, never()).selectById(anyString());
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void backfillCodesByRspu_shouldBackfillPendingRsku() {
        // 补发该 RSPU 下 rsku_code 为空的 RSKU
        RskuSupply pending = rskuWithoutCode("RSKU-TEST01", "PE");
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(pending));
        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(pending);
        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspuWithCode());
        when(rskuCodeMapper.allocateSequence("FS-MC-001-M", "A004", "PE")).thenReturn(1L);

        int backfilled = rskuCodeService.backfillCodesByRspu("RSPU-TEST01");

        assertThat(backfilled).isEqualTo(1);
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRskuCode()).isEqualTo("FS-MC-001-M-A004-PE-001");
    }

    @Test
    void backfillCodesByRspu_shouldReturnZeroWhenNoPendingRsku() {
        // 无空码 RSKU（已有码的被 isNull 条件排除在外）时直接返回 0
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        int backfilled = rskuCodeService.backfillCodesByRspu("RSPU-TEST01");

        assertThat(backfilled).isZero();
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void backfillCodesByRspu_shouldContinueWhenSingleFails() {
        // 单条补发失败（如材质码缺失）仅告警跳过，不影响其余记录补发
        RskuSupply ok = rskuWithoutCode("RSKU-TEST01", "PE");
        RskuSupply bad = rskuWithoutCode("RSKU-TEST02", null);
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(bad, ok));
        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(ok);
        when(rskuSupplyMapper.selectById("RSKU-TEST02")).thenReturn(bad);
        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspuWithCode());
        when(rskuCodeMapper.allocateSequence("FS-MC-001-M", "A004", "PE")).thenReturn(1L);

        int backfilled = rskuCodeService.backfillCodesByRspu("RSPU-TEST01");

        assertThat(backfilled).isEqualTo(1);
        assertThat(ok.getRskuCode()).isEqualTo("FS-MC-001-M-A004-PE-001");
        assertThat(bad.getRskuCode()).isNull();
    }
}
