package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.AgentRskuResolver;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRskuResolver} 单元测试（最低售价 RSKU 解析口径守卫）。
 */
@ExtendWith(MockitoExtension.class)
class AgentRskuResolverTest {

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private PricingService pricingService;

    private AgentRskuResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentRskuResolver(rskuSupplyMapper, rspuMapper, dataScopeHelper, pricingService);
    }

    private RspuMaster rspu(String rspuId) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        return rspu;
    }

    private RskuSupply rsku(String rskuId, String rspuId, String factoryCode) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId(rskuId);
        rsku.setRspuId(rspuId);
        rsku.setFactoryCode(factoryCode);
        return rsku;
    }

    private PricingService.SalePriceDetail detail(String salePrice) {
        return new PricingService.SalePriceDetail(
            salePrice != null ? new BigDecimal(salePrice) : null,
            PricingService.SOURCE_MANUAL, null, null);
    }

    @Test
    void shouldPickLowestPricedRskuPerRspu() {
        when(rspuMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(rspu("RSPU-1")));
        when(rskuSupplyMapper.selectCapableByRspuIds(List.of("RSPU-1")))
            .thenReturn(List.of(
                rsku("RSKU-HI", "RSPU-1", "F1"),
                rsku("RSKU-LO", "RSPU-1", "F2")));
        when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);
        RspuMaster master = rspu("RSPU-1");
        when(pricingService.resolveSalePriceDetail(any(RspuMaster.class), any(RskuSupply.class)))
            .thenAnswer(invocation -> {
                RskuSupply r = invocation.getArgument(1);
                return detail("RSKU-HI".equals(r.getRskuId()) ? "3000" : "2000");
            });

        Map<String, AgentRskuResolver.ResolvedRsku> result = resolver.resolveMinPriceRsku(List.of("RSPU-1"));

        assertThat(result).containsOnlyKeys("RSPU-1");
        assertThat(result.get("RSPU-1").rsku().getRskuId()).isEqualTo("RSKU-LO");
        assertThat(result.get("RSPU-1").price().salePrice()).isEqualByComparingTo("2000");
        assertThat(master).isNotNull();
    }

    @Test
    void inaccessibleFactoryShouldBeSkipped() {
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rspu("RSPU-1")));
        when(rskuSupplyMapper.selectCapableByRspuIds(List.of("RSPU-1")))
            .thenReturn(List.of(rsku("RSKU-1", "RSPU-1", "F-OTHER")));
        when(dataScopeHelper.canAccessFactory("F-OTHER")).thenReturn(false);

        assertThat(resolver.resolveMinPriceRsku(List.of("RSPU-1"))).isEmpty();
    }

    @Test
    void unresolvablePriceShouldBeSkipped() {
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rspu("RSPU-1")));
        when(rskuSupplyMapper.selectCapableByRspuIds(List.of("RSPU-1")))
            .thenReturn(List.of(rsku("RSKU-1", "RSPU-1", "F1")));
        when(dataScopeHelper.canAccessFactory("F1")).thenReturn(true);
        when(pricingService.resolveSalePriceDetail(any(RspuMaster.class), any(RskuSupply.class)))
            .thenReturn(new PricingService.SalePriceDetail(null, PricingService.SOURCE_NONE, null, null));

        assertThat(resolver.resolveMinPriceRsku(List.of("RSPU-1"))).isEmpty();
    }

    @Test
    void emptyInputShouldReturnEmpty() {
        assertThat(resolver.resolveMinPriceRsku(List.of())).isEmpty();
        assertThat(resolver.resolveMinPriceRsku(null)).isEmpty();
    }
}
