package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.PriceHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PriceHistoryController} 单元测试。
 *
 * <p>价格历史为出厂价明文，必须按出厂价可见性（canViewFactoryPrice）门控，
 * 防止设计师等 DataScope=ALL 角色旁路读取全部工厂出厂价历史。</p>
 */
@ExtendWith(MockitoExtension.class)
class PriceHistoryControllerTest {

    @Mock
    private PriceHistoryService priceHistoryService;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @InjectMocks
    private PriceHistoryController priceHistoryController;

    @Test
    void listPriceHistory_shouldReturnHistoryWhenFactoryPriceVisible() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setFactoryCode("F001");
        PriceHistory history = new PriceHistory();
        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(dataScopeHelper.canViewFactoryPrice("F001")).thenReturn(true);
        when(priceHistoryService.listByRsku("RSKU-001")).thenReturn(List.of(history));

        Result<List<PriceHistory>> result = priceHistoryController.listPriceHistory("RSKU-001");

        assertThat(result.getData()).hasSize(1);
    }

    @Test
    void listPriceHistory_shouldRejectWhenFactoryPriceNotVisible() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setFactoryCode("F001");
        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(dataScopeHelper.canViewFactoryPrice("F001")).thenReturn(false);

        assertThatThrownBy(() -> priceHistoryController.listPriceHistory("RSKU-001"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权访问");
        // 不出厂价可见性校验通过前不得查询价格历史
        verify(priceHistoryService, never()).listByRsku(any());
    }

    @Test
    void listPriceHistory_shouldRejectWhenRskuNotFound() {
        when(rskuSupplyMapper.selectById("RSKU-X")).thenReturn(null);

        assertThatThrownBy(() -> priceHistoryController.listPriceHistory("RSKU-X"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RSKU 不存在");
    }
}
