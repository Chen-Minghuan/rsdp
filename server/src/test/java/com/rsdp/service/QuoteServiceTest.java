package com.rsdp.service;

import com.rsdp.dto.request.QuoteItemRequest;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link QuoteService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private FactoryService factoryService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private PricingService pricingService;

    @InjectMocks
    private QuoteService quoteService;

    @Test
    void generateQuote_shouldReturnQuoteWithSummary() {
        when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");
        rsku.setLeadTimeDays(25);
        rsku.setMoq(10);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");
        rspu.setProductName("像素沙发");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(List.of("RSPU-001"))).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of(factory));
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S", "A")));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(image));

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 2)));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("5000"));
        // 完整商品名称透出（rspuName 保持定位标签不动）
        assertThat(response.getItems().get(0).getProductName()).isEqualTo("像素沙发");
        assertThat(response.getItems().get(0).getRspuName()).isEqualTo("中古风");
        assertThat(response.getSummary().getTotalPrice()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(response.getSummary().getItemCount()).isEqualTo(1);
        assertThat(response.getSummary().getTotalQuantity()).isEqualTo(2);
        assertThat(response.getSummary().getFactoryCount()).isEqualTo(1);
        assertThat(response.getSummary().getMaxLeadTimeDays()).isEqualTo(25);
    }

    @Test
    void generateQuote_shouldRejectEmptyList() {
        assertThatThrownBy(() -> quoteService.generateQuote(List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("至少一个");
    }

    @Test
    void generateQuote_shouldThrowWhenRskuNotFound() {
        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-NOT-FOUND"))).thenReturn(List.of());

        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-NOT-FOUND", 1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已失效或不存在")
            .hasMessageContaining("RSKU-NOT-FOUND");
    }

    @Test
    void generateQuote_shouldCollectAllInvalidRskuIds() {
        // @TableLogic 下 selectBatchIds 不会返回已软删记录，失效 RSKU 表现为查询结果缺失
        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-A", "RSKU-B"))).thenReturn(List.of());

        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-A", 1), req("RSKU-B", 1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RSKU-A")
            .hasMessageContaining("RSKU-B");
    }

    @Test
    void generateQuote_shouldRejectRskuWhenFactoryNotCapable() {
        when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("A", "B")));

        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-001", 1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RSKU-001")
            .hasMessageContaining("未声明");
    }

    @Test
    void generateQuote_shouldMergeDuplicateRskuQuantities() {
        when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(List.of("RSPU-001"))).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of(factory));
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S", "A")));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 2), req("RSKU-001", 3)));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("12500"));
        assertThat(response.getSummary().getTotalQuantity()).isEqualTo(5);
    }

    @Test
    void generateQuote_shouldDefaultNullOrZeroQuantityToOne() {
        when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(List.of("RSPU-001"))).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of(factory));
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S", "A")));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        QuoteItemRequest nullQty = new QuoteItemRequest();
        nullQty.setRskuId("RSKU-001");
        nullQty.setQuantity(null);
        QuoteItemRequest zeroQty = new QuoteItemRequest();
        zeroQty.setRskuId("RSKU-001");
        zeroQty.setQuantity(0);

        var response = quoteService.generateQuote(List.of(nullQty, zeroQty));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    private QuoteItemRequest req(String rskuId, int quantity) {
        QuoteItemRequest r = new QuoteItemRequest();
        r.setRskuId(rskuId);
        r.setQuantity(quantity);
        return r;
    }

    /** sale 口径公共数据准备：RSKU 成本 2500，RSPU 中古风，工厂 F001 具备 S 级能力。 */
    private void stubSaleModeFixtures() {
        when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectBatchIds(List.of("RSKU-001"))).thenReturn(List.of(rsku));
        when(rspuMapper.selectBatchIds(List.of("RSPU-001"))).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of(factory));
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S", "A")));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void generateQuote_saleMode_shouldPriceBySalePriceWithMargin() {
        stubSaleModeFixtures();
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);
        // 标准售价 6000（建议销售价或成本×倍率由 PricingService 决定）
        when(pricingService.resolveSalePrice(any(), any())).thenReturn(new BigDecimal("6000"));

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 2)), "sale");

        var item = response.getItems().get(0);
        // 单价/小计即售价口径
        assertThat(item.getSalePrice()).isEqualByComparingTo(new BigDecimal("6000"));
        assertThat(item.getSubtotal()).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(item.isBelowCost()).isFalse();
        // 有出厂价权限：附成本与毛利（仅内部）
        assertThat(item.getCostPrice()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(item.getMarginAmount()).isEqualByComparingTo(new BigDecimal("3500.00"));
        // 汇总：成本合计 5000，毛利合计 7000
        assertThat(response.getSummary().getTotalPrice()).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(response.getSummary().getTotalCost()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(response.getSummary().getTotalMargin()).isEqualByComparingTo(new BigDecimal("7000.00"));
        assertThat(response.getPriceWarning()).isNull();
    }

    @Test
    void generateQuote_saleMode_shouldRejectUnpricedRsku() {
        stubSaleModeFixtures();
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);
        when(pricingService.resolveSalePrice(any(), any())).thenReturn(null);

        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-001", 1)), "sale"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未定价")
            .hasMessageContaining("RSPU-001");
    }

    @Test
    void generateQuote_saleMode_shouldHideCostFieldsWithoutFactoryPricePermission() {
        stubSaleModeFixtures();
        // 无出厂价查看权限：绝不返回成本/毛利字段
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(false);
        when(pricingService.resolveSalePrice(any(), any())).thenReturn(new BigDecimal("6000"));

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 2)), "sale");

        var item = response.getItems().get(0);
        assertThat(item.getSalePrice()).isEqualByComparingTo(new BigDecimal("6000"));
        assertThat(item.getSubtotal()).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(item.getFactoryPrice()).isNull();
        assertThat(item.getCostPrice()).isNull();
        assertThat(item.getMarginAmount()).isNull();
        assertThat(response.getSummary().getTotalCost()).isNull();
        assertThat(response.getSummary().getTotalMargin()).isNull();
    }

    @Test
    void generateQuote_saleMode_shouldWarnWhenSalePriceBelowCost() {
        stubSaleModeFixtures();
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);
        // 清库存：售价 2000 低于成本 2500
        when(pricingService.resolveSalePrice(any(), any())).thenReturn(new BigDecimal("2000"));

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 1)), "sale");

        var item = response.getItems().get(0);
        assertThat(item.isBelowCost()).isTrue();
        assertThat(item.getMarginAmount()).isEqualByComparingTo(new BigDecimal("-500.00"));
        assertThat(response.getPriceWarning())
            .contains("售价低于成本")
            .contains("中古风")
            .contains("清库存");
    }

    @Test
    void generateQuote_shouldRejectInvalidMode() {
        assertThatThrownBy(() -> quoteService.generateQuote(List.of(req("RSKU-001", 1)), "retail"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非法报价口径");
    }

    @Test
    void generateQuote_costMode_shouldNotReturnSaleFields() {
        stubSaleModeFixtures();
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        var response = quoteService.generateQuote(List.of(req("RSKU-001", 2)), "cost");

        var item = response.getItems().get(0);
        assertThat(item.getSubtotal()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(item.getSalePrice()).isNull();
        assertThat(item.getCostPrice()).isNull();
        assertThat(item.getMarginAmount()).isNull();
        assertThat(response.getSummary().getTotalCost()).isNull();
        assertThat(response.getPriceWarning()).isNull();
    }
}
