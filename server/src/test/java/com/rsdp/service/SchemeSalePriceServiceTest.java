package com.rsdp.service;

import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.SchemeItem;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeItemMapper;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SchemeSalePriceService} 单元测试（售价合计实时换算口径）。
 *
 * <p>使用真实 {@link PricingService}（仅 mock 其配置依赖），验证三级售价解析链在求和场景的实际结果。</p>
 */
@ExtendWith(MockitoExtension.class)
class SchemeSalePriceServiceTest {

    @Mock
    private SchemeItemMapper schemeItemMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private ConfigService configService;

    @Mock
    private PricingRuleService pricingRuleService;

    private SchemeSalePriceService schemeSalePriceService;

    @BeforeEach
    void setUp() {
        // 真实 PricingService：全局倍率 2.5，无品类级规则（走全局兜底）
        lenient().when(configService.getGlobalMarkupMultiplier()).thenReturn(new BigDecimal("2.5"));
        lenient().when(pricingRuleService.listAllRules()).thenReturn(Map.of());
        PricingService pricingService = new PricingService(configService, pricingRuleService);
        schemeSalePriceService = new SchemeSalePriceService(
            schemeItemMapper, rspuMapper, rskuSupplyMapper, pricingService);
    }

    private SchemeItem item(String schemeId, String rspuId, String rskuId, Integer quantity) {
        SchemeItem item = new SchemeItem();
        item.setSchemeId(schemeId);
        item.setRspuId(rspuId);
        item.setRskuId(rskuId);
        item.setQuantity(quantity);
        return item;
    }

    private RspuMaster rspu(String rspuId, String categoryCode, BigDecimal retailPrice) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode(categoryCode);
        rspu.setRetailPrice(retailPrice);
        return rspu;
    }

    private RskuSupply rsku(String rskuId, String rspuId, BigDecimal factoryPrice) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId(rskuId);
        rsku.setRspuId(rspuId);
        rsku.setFactoryPrice(factoryPrice);
        return rsku;
    }

    @Test
    void batchTotalSalePrices_shouldSumSalePriceByQuantityPerScheme() {
        // 方案一：retail_price 优先（1000 × 2）；方案二：成本 × 全局倍率（200 × 2.5 = 500）
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(
            item("SCHEME-1", "RSPU-1", "RSKU-1", 2),
            item("SCHEME-2", "RSPU-2", "RSKU-2", 1)));
        when(rspuMapper.selectList(any())).thenReturn(List.of(
            rspu("RSPU-1", "SF", new BigDecimal("1000")),
            rspu("RSPU-2", "TB", null)));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(
            rsku("RSKU-1", "RSPU-1", new BigDecimal("400")),
            rsku("RSKU-2", "RSPU-2", new BigDecimal("200"))));

        Map<String, BigDecimal> result = schemeSalePriceService.batchTotalSalePrices(
            List.of("SCHEME-1", "SCHEME-2"));

        assertThat(result.get("SCHEME-1")).isEqualByComparingTo("2000");
        assertThat(result.get("SCHEME-2")).isEqualByComparingTo("500.00");
    }

    @Test
    void batchTotalSalePrices_shouldSkipUnpricedItems() {
        // 无建议销售价且无成本 → 未定价，跳过求和；方案一只计已定价项
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(
            item("SCHEME-1", "RSPU-1", "RSKU-1", 1),
            item("SCHEME-1", "RSPU-2", "RSKU-2", 3)));
        when(rspuMapper.selectList(any())).thenReturn(List.of(
            rspu("RSPU-1", "SF", null),
            rspu("RSPU-2", "TB", null)));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(
            rsku("RSKU-1", "RSPU-1", new BigDecimal("100")),
            rsku("RSKU-2", "RSPU-2", null)));

        Map<String, BigDecimal> result = schemeSalePriceService.batchTotalSalePrices(List.of("SCHEME-1"));

        // 仅已定价项计入：100 × 2.5 = 250
        assertThat(result.get("SCHEME-1")).isEqualByComparingTo("250.00");
    }

    @Test
    void batchTotalSalePrices_shouldReturnEmptyWhenNoSchemesOrItems() {
        assertThat(schemeSalePriceService.batchTotalSalePrices(List.of())).isEmpty();

        when(schemeItemMapper.selectList(any())).thenReturn(List.of());
        assertThat(schemeSalePriceService.batchTotalSalePrices(List.of("SCHEME-9"))).isEmpty();
    }

    @Test
    void batchTotalSalePrices_shouldTreatNullOrNonPositiveQuantityAsOne() {
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(
            item("SCHEME-1", "RSPU-1", "RSKU-1", null),
            item("SCHEME-1", "RSPU-1", "RSKU-1", 0)));
        when(rspuMapper.selectList(any())).thenReturn(List.of(
            rspu("RSPU-1", "SF", new BigDecimal("300"))));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(
            rsku("RSKU-1", "RSPU-1", new BigDecimal("100"))));

        Map<String, BigDecimal> result = schemeSalePriceService.batchTotalSalePrices(List.of("SCHEME-1"));

        // 两行均按数量 1 计：300 × 1 × 2
        assertThat(result.get("SCHEME-1")).isEqualByComparingTo("600");
    }

    @Test
    void sumItemsSalePrice_shouldReuseProvidedMapsAndSkipUnpriced() {
        List<SchemeItem> items = List.of(
            item("SCHEME-1", "RSPU-1", "RSKU-1", 2),
            item("SCHEME-1", "RSPU-2", "RSKU-2", 1));
        Map<String, RspuMaster> rspuMap = Map.of(
            "RSPU-1", rspu("RSPU-1", "SF", new BigDecimal("800")),
            "RSPU-2", rspu("RSPU-2", "TB", null));
        Map<String, RskuSupply> rskuMap = Map.of(
            "RSKU-1", rsku("RSKU-1", "RSPU-1", new BigDecimal("300")),
            "RSKU-2", rsku("RSKU-2", "RSPU-2", null));

        BigDecimal total = schemeSalePriceService.sumItemsSalePrice(items, rspuMap, rskuMap);

        // RSPU-1：800 × 2 = 1600；RSPU-2 未定价跳过
        assertThat(total).isEqualByComparingTo("1600");
    }
}
