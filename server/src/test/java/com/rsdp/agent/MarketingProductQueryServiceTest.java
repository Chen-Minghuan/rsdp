package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.domain.MarketingProductQueryService;
import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.agent.domain.ProductVisibilityPolicy;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.service.DictAliasService;
import com.rsdp.service.DictResolverService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MarketingProductQueryService} 单元测试（结构化过滤 + 尺寸硬过滤 + 别名归一）。
 */
@ExtendWith(MockitoExtension.class)
class MarketingProductQueryServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuVariantMapper rspuVariantMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private DictAliasService dictAliasService;

    @Mock
    private DictResolverService dictResolverService;

    @Mock
    private ProductVisibilityPolicy visibilityPolicy;

    private final MarketingAgentProperties properties = new MarketingAgentProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MarketingProductQueryService service;

    @BeforeEach
    void setUp() {
        service = new MarketingProductQueryService(rspuMapper, rspuVariantMapper, imageAssetsMapper,
            dictAliasService, dictResolverService, visibilityPolicy, properties, objectMapper);
    }

    private RspuMaster rspu(String rspuId, String name) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setProductName(name);
        rspu.setRetailPrice(new BigDecimal("15000"));
        return rspu;
    }

    private RspuVariant variant(String rspuId, String dimensionsJson, String sizeText) {
        RspuVariant variant = new RspuVariant();
        variant.setRspuId(rspuId);
        variant.setDimensions(dimensionsJson);
        variant.setSizeText(sizeText);
        return variant;
    }

    @Test
    void budgetMaxShouldPushDownRetailPriceLeCondition() {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setBudgetMax(new BigDecimal("20000"));
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ProductSearchResult result = service.search(criteria);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("retail_price <=");
        assertThat(captor.getValue().getParamNameValuePairs())
            .containsValue(new BigDecimal("20000"));
        // 可见性收口必须经过 ProductVisibilityPolicy
        verify(visibilityPolicy).applyScope(any(QueryWrapper.class));
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotalMatched()).isZero();
    }

    @Test
    void styleAndMaterialShouldBeNormalizedViaDictAlias() {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setStyle("奶油风");
        criteria.setMaterial("头层牛皮");
        when(dictAliasService.resolveAliases(eq("style"), eq(List.of("奶油风"))))
            .thenReturn(Map.of("奶油风", "cream"));
        when(dictAliasService.resolveAliases(eq("material"), eq(List.of("头层牛皮"))))
            .thenReturn(Map.of("头层牛皮", "top_grain_leather"));
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.search(criteria);

        // 别名归一被调用（style/material 两个字典类型）
        verify(dictAliasService).resolveAliases(eq("style"), eq(List.of("奶油风")));
        verify(dictAliasService).resolveAliases(eq("material"), eq(List.of("头层牛皮")));

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        // jsonb 列无 LIKE 运算符，必须显式 ::text 转换（否则 PG 报 jsonb ~~ unknown）
        assertThat(sqlSegment).contains("six_dim_tags::text").contains("material_tags::text");
        // 归一后的字典码参与匹配，而非方言原文
        assertThat(captor.getValue().getParamNameValuePairs())
            .containsValue("%cream%")
            .containsValue("%top_grain_leather%");
    }

    @Test
    void categoryNameShouldBeNormalizedToDictCode() {
        // LLM 抽取的是品类词（沙发/座椅），category_code 列存字典码（SF/FS），需经 category_dict 归一
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setCategoryCode("沙发");
        when(dictResolverService.resolveCodeByName("category", "沙发")).thenReturn("SF");
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.search(criteria);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("category_code =");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue("SF");
    }

    @Test
    void unknownCategoryTextShouldFallBackToRawValue() {
        // 字典未收录的品类词：保留原文等值匹配（结果为空，但不报错、不静默放宽过滤）
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setCategoryCode("火星沙发");
        when(dictResolverService.resolveCodeByName("category", "火星沙发")).thenReturn(null);
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.search(criteria);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuMapper).selectList(captor.capture());
        // 参数名在 getSqlSegment() 渲染时才生成，须先取 SQL 片段再断言参数
        assertThat(captor.getValue().getSqlSegment()).contains("category_code =");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue("火星沙发");
    }

    @Test
    void sizeHardFilterShouldExcludeOversizeAndUnparseable() {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setMaxWidthMm(2000);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rspu("RSPU-1", "合规沙发"),
            rspu("RSPU-2", "超宽沙发"),
            rspu("RSPU-3", "无尺寸沙发")
        ));
        when(rspuVariantMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            variant("RSPU-1", "{\"w\":1800,\"d\":900,\"h\":800,\"unit\":\"mm\"}", "1800*900*800"),
            variant("RSPU-2", "{\"w\":2380,\"d\":900,\"h\":800,\"unit\":\"mm\"}", "2380*900*800"),
            variant("RSPU-3", "not-json", null)
        ));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ProductSearchResult result = service.search(criteria);

        assertThat(result.getTotalMatched()).isEqualTo(1);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getRspuId()).isEqualTo("RSPU-1");
        assertThat(result.getItems().get(0).getSizeText()).isEqualTo("1800*900*800");
        assertThat(result.getItems().get(0).getMatchedConditions()).contains("宽度≤2000mm");
    }

    @Test
    void sizeHardFilterShouldConvertCmToMm() {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setMaxWidthMm(2000);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rspu("RSPU-1", "厘米单位沙发"),
            rspu("RSPU-2", "厘米超宽沙发")
        ));
        when(rspuVariantMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            variant("RSPU-1", "{\"w\":180,\"d\":90,\"unit\":\"cm\"}", "180*90"),
            variant("RSPU-2", "{\"w\":250,\"d\":90,\"unit\":\"cm\"}", "250*90")
        ));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ProductSearchResult result = service.search(criteria);

        // 180cm=1800mm 保留；250cm=2500mm 超宽排除
        assertThat(result.getItems()).extracting("rspuId").containsExactly("RSPU-1");
        assertThat(result.getTotalMatched()).isEqualTo(1);
    }

    @Test
    void sizeHardFilterShouldRespectMinWidth() {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setMinWidthMm(2000);
        criteria.setMaxWidthMm(3000);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rspu("RSPU-1", "过窄沙发"),
            rspu("RSPU-2", "合规沙发")
        ));
        when(rspuVariantMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            variant("RSPU-1", "{\"w\":1800,\"d\":900,\"unit\":\"mm\"}", "1800*900"),
            variant("RSPU-2", "{\"w\":2400,\"d\":900,\"unit\":\"mm\"}", "2400*900")
        ));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ProductSearchResult result = service.search(criteria);

        assertThat(result.getItems()).extracting("rspuId").containsExactly("RSPU-2");
        assertThat(result.getItems().get(0).getMatchedConditions()).contains("宽度2000~3000mm");
    }

    @Test
    void sizeHardFilterShouldTruncateToTopNAfterPreciseFiltering() {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setMaxWidthMm(3000);
        criteria.setTopN(1);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rspu("RSPU-1", "沙发一"),
            rspu("RSPU-2", "沙发二")
        ));
        when(rspuVariantMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            variant("RSPU-1", "{\"w\":2000,\"d\":900,\"unit\":\"mm\"}", "2000*900"),
            variant("RSPU-2", "{\"w\":2100,\"d\":900,\"unit\":\"mm\"}", "2100*900")
        ));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ProductSearchResult result = service.search(criteria);

        // totalMatched 为截断前真实总数，items 按 topN 截断
        assertThat(result.getTotalMatched()).isEqualTo(2);
        assertThat(result.getItems()).hasSize(1);
    }

    @Test
    void representativeWidthShouldTakeMaxAcrossVariants() {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        criteria.setMaxWidthMm(2200);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rspu("RSPU-1", "多变体沙发")
        ));
        // 一个变体 1800mm、另一个 2380mm：取最大宽度 2380 > 2200，整品排除
        when(rspuVariantMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            variant("RSPU-1", "{\"w\":1800,\"d\":900,\"unit\":\"mm\"}", "1800*900"),
            variant("RSPU-1", "{\"w\":2380,\"d\":900,\"unit\":\"mm\"}", "2380*900")
        ));

        ProductSearchResult result = service.search(criteria);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotalMatched()).isZero();
    }

    @Test
    void emptyCriteriaShouldUseDefaultTopNAndVisibilityScope() {
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(3L);
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ProductSearchResult result = service.search(null);

        assertThat(result.getTotalMatched()).isEqualTo(3);
        verify(visibilityPolicy).applyScope(any(QueryWrapper.class));
    }
}
