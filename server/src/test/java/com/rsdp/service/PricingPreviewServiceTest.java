package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.common.PageResult;
import com.rsdp.dto.response.PricingPreviewItemResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.PricingRule;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.SysConfig;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SysConfigMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PricingPreviewService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PricingPreviewServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private CategoryDictMapper categoryDictMapper;

    @Mock
    private SysConfigMapper sysConfigMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PricingRuleService pricingRuleService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    private PricingPreviewService previewService;

    @BeforeEach
    void setUp() {
        PricingService pricingService =
            new PricingService(new ConfigService(sysConfigMapper, auditLogService), pricingRuleService);
        previewService = new PricingPreviewService(rspuMapper, rskuSupplyMapper, categoryDictMapper,
            pricingService, pricingRuleService, dataScopeHelper);
        lenient().when(pricingRuleService.listAllRules()).thenReturn(Map.of());
        lenient().when(sysConfigMapper.selectById(ConfigService.MARKUP_GLOBAL_KEY)).thenReturn(null);
        lenient().when(categoryDictMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    }

    private RspuMaster rspu(String rspuId, String categoryCode, String retailPrice) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setRspuCode("CODE-" + rspuId);
        rspu.setProductName("产品" + rspuId);
        rspu.setCategoryCode(categoryCode);
        if (retailPrice != null) {
            rspu.setRetailPrice(new BigDecimal(retailPrice));
        }
        return rspu;
    }

    private RskuSupply rsku(String rspuId, String factoryPrice) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-" + rspuId);
        rsku.setRspuId(rspuId);
        rsku.setFactoryCode("F001");
        if (factoryPrice != null) {
            rsku.setFactoryPrice(new BigDecimal(factoryPrice));
        }
        return rsku;
    }

    @SuppressWarnings("unchecked")
    private void stubPage(List<RspuMaster> records, long total) {
        Page<RspuMaster> page = Page.of(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        when(rspuMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
    }

    @Test
    void previewShouldResolveSourcesAndMaskCostByPermission() {
        // 两个产品：RSPU-1 人工定价 900（成本 1000，低于成本）；RSPU-2 自动计价（成本 1000 × 2.5）
        stubPage(List.of(rspu("RSPU-1", "FS", "900"), rspu("RSPU-2", "FS", null)), 2);
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(rsku("RSPU-1", "1000"), rsku("RSPU-2", "1000")));
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        PageResult<PricingPreviewItemResponse> result = previewService.preview(null, null, null, 1, 20);

        assertThat(result.getTotal()).isEqualTo(2);
        PricingPreviewItemResponse manual = result.getRows().get(0);
        assertThat(manual.getPriceSource()).isEqualTo(PricingService.SOURCE_MANUAL);
        assertThat(manual.getSalePrice()).isEqualByComparingTo("900");
        assertThat(manual.getAppliedMultiplier()).isNull();
        assertThat(manual.getCostPrice()).isEqualByComparingTo("1000");
        assertThat(manual.isBelowCost()).isTrue();
        // (900-1000)/900 = -0.1111
        assertThat(manual.getMarginRate()).isEqualByComparingTo("-0.1111");

        PricingPreviewItemResponse auto = result.getRows().get(1);
        assertThat(auto.getPriceSource()).isEqualTo(PricingService.SOURCE_GLOBAL);
        assertThat(auto.getSalePrice()).isEqualByComparingTo("2500.00");
        assertThat(auto.getAppliedMultiplier()).isEqualByComparingTo("2.5");
        assertThat(auto.getMarginRate()).isEqualByComparingTo("0.6000");
        assertThat(auto.isBelowCost()).isFalse();
    }

    @Test
    void previewShouldPickMinCostRsku() {
        // 多 RSKU 时取成本最低者（与产品列表"最低出厂价"口径一致）
        stubPage(List.of(rspu("RSPU-1", "FS", null)), 1);
        RskuSupply expensive = rsku("RSPU-1", "2000");
        RskuSupply cheap = rsku("RSPU-1", "800");
        cheap.setRskuId("RSKU-CHEAP");
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(expensive, cheap));
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        PageResult<PricingPreviewItemResponse> result = previewService.preview(null, null, null, 1, 20);

        // 800 × 2.5 = 2000.00
        assertThat(result.getRows().get(0).getCostPrice()).isEqualByComparingTo("800");
        assertThat(result.getRows().get(0).getSalePrice()).isEqualByComparingTo("2000.00");
    }

    @Test
    void previewShouldUseCategoryRuleWhenHit() {
        when(pricingRuleService.listAllRules()).thenReturn(Map.of("FS", categoryRule("FS", "3.0")));
        stubPage(List.of(rspu("RSPU-1", "FS", null)), 1);
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rsku("RSPU-1", "1000")));
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        PageResult<PricingPreviewItemResponse> result = previewService.preview(null, null, null, 1, 20);

        PricingPreviewItemResponse row = result.getRows().get(0);
        assertThat(row.getPriceSource()).isEqualTo(PricingService.SOURCE_CATEGORY_RULE);
        assertThat(row.getSalePrice()).isEqualByComparingTo("3000.00");
        assertThat(row.getAppliedMultiplier()).isEqualByComparingTo("3.0");
    }

    @Test
    void previewShouldHideCostAndMarginWithoutFactoryPricePermission() {
        // 无出厂价权限：不返回成本与毛利率；售价与 belowCost 仍返回
        stubPage(List.of(rspu("RSPU-1", "FS", "900")), 1);
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rsku("RSPU-1", "1000")));
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(false);

        PageResult<PricingPreviewItemResponse> result = previewService.preview(null, null, null, 1, 20);

        PricingPreviewItemResponse row = result.getRows().get(0);
        assertThat(row.getCostPrice()).isNull();
        assertThat(row.getMarginRate()).isNull();
        assertThat(row.getSalePrice()).isEqualByComparingTo("900");
        assertThat(row.isBelowCost()).isTrue();
    }

    @Test
    void previewShouldPushSourceFilterToSql() {
        stubPage(List.of(), 0);

        previewService.preview(null, PricingService.SOURCE_MANUAL, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<RspuMaster>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("retail_price IS NOT NULL");
    }

    @Test
    void previewShouldReturnEmptyWhenCategoryRuleFilterHasNoRules() {
        // CATEGORY_RULE 筛选但无任何品类规则：结果恒为空，且不查库
        PageResult<PricingPreviewItemResponse> result =
            previewService.preview(null, PricingService.SOURCE_CATEGORY_RULE, null, 1, 20);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRows()).isEmpty();
        verify(rspuMapper, never()).selectPage(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void previewShouldRejectInvalidSource() {
        assertThatThrownBy(() -> previewService.preview(null, "retail", null, 1, 20))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非法售价来源");
    }

    @Test
    void summaryShouldCountBySource() {
        // RSPU-1 人工 900（成本 1000，低于成本）；RSPU-2 品类规则 3.0；RSPU-3 全局；RSPU-4 未定价
        when(pricingRuleService.listAllRules()).thenReturn(Map.of("FS", categoryRule("FS", "3.0")));
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rspu("RSPU-1", "FS", "900"),
            rspu("RSPU-2", "FS", null),
            rspu("RSPU-3", "CH", null),
            rspu("RSPU-4", "CH", null)
        ));
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rsku("RSPU-1", "1000"),
            rsku("RSPU-2", "1000"),
            rsku("RSPU-3", "1000")
        ));

        var summary = previewService.summary();

        assertThat(summary.getManual()).isEqualTo(1);
        assertThat(summary.getCategoryRule()).isEqualTo(1);
        assertThat(summary.getGlobal()).isEqualTo(1);
        assertThat(summary.getUnpriced()).isEqualTo(1);
        assertThat(summary.getBelowCost()).isEqualTo(1);
        assertThat(summary.getTotal()).isEqualTo(4);
    }

    private PricingRule categoryRule(String categoryCode, String multiplier) {
        PricingRule rule = new PricingRule();
        rule.setRuleId("PRULE-" + categoryCode);
        rule.setCategoryCode(categoryCode);
        rule.setMarkupMultiplier(new BigDecimal(multiplier));
        return rule;
    }
}
