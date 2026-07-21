package com.rsdp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.request.ProductUpdateRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.entity.FactoryProductCapability;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.FactoryProductCapabilityMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.dto.response.RspuRelationResponse;
import com.rsdp.event.RspuDeletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductQueryService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    @BeforeEach
    void setSecurityContext() {
        var user = User.withUsername("admin").password("").roles("ADMIN").build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        lenient().when(userFactoryService.getFactoryCodesByUsername("admin")).thenReturn(List.of());
        lenient().when(dataScopeHelper.canAccessRspu(any())).thenReturn(true);
        lenient().when(dataScopeHelper.isOnlyAssociatedFactoryForRspu(any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private AiRecognitionMapper aiRecognitionMapper;

    @Mock
    private RspuStyleMapper rspuStyleMapper;

    @Mock
    private RspuSceneMapper rspuSceneMapper;

    @Mock
    private ProductStyleMatchMapper productStyleMatchMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private DictService dictService;

    @Mock
    private RspuRelationService rspuRelationService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserFactoryService userFactoryService;

    @Mock
    private FactoryProductCapabilityMapper capabilityMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProductQueryService productQueryService;

    private List<com.rsdp.entity.CategoryDict> factoryLevelDicts() {
        com.rsdp.entity.CategoryDict s = new com.rsdp.entity.CategoryDict();
        s.setDictType("factory_level");
        s.setDictCode("S");
        s.setDictName("S级");
        com.rsdp.entity.CategoryDict c = new com.rsdp.entity.CategoryDict();
        c.setDictType("factory_level");
        c.setDictCode("C");
        c.setDictName("C级");
        return List.of(s, c);
    }

    private List<com.rsdp.entity.CategoryDict> styleDicts() {
        com.rsdp.entity.CategoryDict mc = new com.rsdp.entity.CategoryDict();
        mc.setDictType("style");
        mc.setDictCode("MC");
        mc.setDictName("现代简约");
        return List.of(mc);
    }

    private List<com.rsdp.entity.CategoryDict> sceneDicts() {
        com.rsdp.entity.CategoryDict living = new com.rsdp.entity.CategoryDict();
        living.setDictType("scene");
        living.setDictCode("LIVING");
        living.setDictName("客厅");
        return List.of(living);
    }

    private List<com.rsdp.entity.CategoryDict> materialDicts() {
        com.rsdp.entity.CategoryDict wo = new com.rsdp.entity.CategoryDict();
        wo.setDictType("material");
        wo.setDictCode("WO");
        wo.setDictName("实木");
        return List.of(wo);
    }

    @Test
    void listProducts_shouldReturnPageResult() {
        ProductListRequest request = new ProductListRequest();
        request.setPage(1L);
        request.setSize(10L);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setCategoryCode("FS");
        rspu.setCategoryPath("[\"家具\",\"座椅\"]");
        rspu.setPositioningLabel("MC");
        rspu.setStatus("active");
        rspu.setReviewStatus("待复核");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getRspuId()).isEqualTo("RSPU-TEST01");
    }

    @Test
    void listProducts_shouldFilterByStyleCode() {
        ProductListRequest request = new ProductListRequest();
        request.setPage(1L);
        request.setSize(10L);
        request.setPositioningLabel("MC");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setStatus("active");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getPositioningLabel()).isEqualTo("MC");
    }

    @Test
    void listProducts_shouldFilterByMaterialTag() {
        ProductListRequest request = new ProductListRequest();
        request.setPage(1L);
        request.setSize(10L);
        request.setMaterialTag("WO");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setMaterialTags("[\"WO\",\"LI\"]");
        rspu.setStatus("active");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        assertThat(result.getRows()).hasSize(1);
    }

    @Test
    void listProducts_shouldFilterBySceneCode() {
        ProductListRequest request = new ProductListRequest();
        request.setPage(1L);
        request.setSize(10L);
        request.setSceneCode("LIVING");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setStatus("active");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getRspuId()).isEqualTo("RSPU-TEST01");
    }

    @Test
    void getProductDetail_shouldReturnDetail() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setStatus("active");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(aiRecognitionMapper.selectList(any())).thenReturn(List.of());
        when(productStyleMatchMapper.selectByRspuId("RSPU-TEST01")).thenReturn(List.of());
        when(rspuRelationService.listByAnchor("RSPU-TEST01")).thenReturn(List.of());
        when(rspuRelationService.listByRelated("RSPU-TEST01")).thenReturn(List.of());

        ProductDetailResponse detail = productQueryService.getProductDetail("RSPU-TEST01");

        assertThat(detail.getRspu()).isNotNull();
        assertThat(detail.getImages()).isEmpty();
        assertThat(detail.getRecognitions()).isEmpty();
        assertThat(detail.getStyleMatches()).isEmpty();
    }

    @Test
    void getProductDetail_shouldIncludeRelations() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-BED");
        rspu.setStatus("active");

        RspuRelationResponse match = new RspuRelationResponse();
        match.setRelationId("REL-001");
        match.setRelatedRspuId("RSPU-MATTRESS");

        RspuRelationResponse matchedBy = new RspuRelationResponse();
        matchedBy.setRelationId("REL-002");
        matchedBy.setAnchorRspuId("RSPU-TABLE");

        when(rspuMapper.selectById(eq("RSPU-BED"))).thenReturn(rspu);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(aiRecognitionMapper.selectList(any())).thenReturn(List.of());
        when(productStyleMatchMapper.selectByRspuId("RSPU-BED")).thenReturn(List.of());
        when(rspuRelationService.listByAnchor("RSPU-BED")).thenReturn(List.of(match));
        when(rspuRelationService.listByRelated("RSPU-BED")).thenReturn(List.of(matchedBy));

        ProductDetailResponse detail = productQueryService.getProductDetail("RSPU-BED");

        assertThat(detail.getOfficialMatches()).hasSize(1);
        assertThat(detail.getOfficialMatches().get(0).getRelatedRspuId()).isEqualTo("RSPU-MATTRESS");
        assertThat(detail.getMatchedBy()).hasSize(1);
        assertThat(detail.getMatchedBy().get(0).getAnchorRspuId()).isEqualTo("RSPU-TABLE");
    }

    @Test
    void getProductDetail_shouldThrowWhenNotFound() {
        when(rspuMapper.selectById(eq("RSPU-NOTEXIST"))).thenReturn(null);

        assertThatThrownBy(() -> productQueryService.getProductDetail("RSPU-NOTEXIST"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reviewProduct_shouldUpdateReviewStatus() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setReviewStatus("待复核");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);

        productQueryService.reviewProduct("RSPU-TEST01", "已确认", "人工复核通过");

        assertThat(rspu.getReviewStatus()).isEqualTo("已确认");
    }

    @Test
    void updateProduct_shouldUpdateFieldsAndRelations() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("OLD");
        rspu.setSceneTags("[]");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(dictService.listByType("style")).thenReturn(styleDicts());
        when(dictService.listByType("scene")).thenReturn(sceneDicts());
        when(dictService.listByType("material")).thenReturn(materialDicts());

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setPositioningLabel("MC");
        request.setColorPrimaryName("原木色");
        request.setMaterialTags(List.of("WO"));
        request.setSceneTags(List.of("LIVING"));
        request.setSixDimTags(Map.of("A", "现代"));
        request.setReferencePriceBand("mid");
        request.setWarrantyYears(3);
        request.setKeySpecs(Map.of("width", "80cm"));

        productQueryService.updateProduct("RSPU-TEST01", request);

        assertThat(rspu.getPositioningLabel()).isEqualTo("MC");
        assertThat(rspu.getColorPrimaryName()).isEqualTo("原木色");
        assertThat(rspu.getMaterialTags()).isEqualTo("[\"WO\"]");
        assertThat(rspu.getSceneTags()).isEqualTo("[\"LIVING\"]");
        assertThat(rspu.getSixDimTags()).isEqualTo("{\"A\":\"现代\"}");
        assertThat(rspu.getReferencePriceBand()).isEqualTo("mid");
        assertThat(rspu.getWarrantyYears()).isEqualTo(3);
        assertThat(rspu.getKeySpecs()).isEqualTo("{\"width\":\"80cm\"}");

        verify(rspuStyleMapper).delete(any());
        verify(rspuStyleMapper).insert(any(RspuStyle.class));
        verify(rspuSceneMapper).delete(any());
        verify(rspuSceneMapper).insert(any(RspuScene.class));
        verify(auditLogService).logUpdate(eq("rspu_master"), eq("RSPU-TEST01"), any(), eq(rspu), eq("admin"));
    }

    @Test
    void updateProduct_shouldUpdateMultipleStylesWithFirstAsPrimary() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("OLD");
        rspu.setSceneTags("[]");

        com.rsdp.entity.CategoryDict cr = new com.rsdp.entity.CategoryDict();
        cr.setDictType("style");
        cr.setDictCode("CR");
        cr.setDictName("奶油风");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(dictService.listByType("style")).thenReturn(List.of(styleDicts().get(0), cr));

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setStyleCodes(List.of("MC", "CR"));

        productQueryService.updateProduct("RSPU-TEST01", request);

        // 首值为主风格写 positioning_label
        assertThat(rspu.getPositioningLabel()).isEqualTo("MC");

        // rspu_style 全量重写：MC 主、CR 辅
        ArgumentCaptor<RspuStyle> styleCaptor = ArgumentCaptor.forClass(RspuStyle.class);
        verify(rspuStyleMapper).delete(any());
        verify(rspuStyleMapper, times(2)).insert(styleCaptor.capture());
        assertThat(styleCaptor.getAllValues().get(0).getStyleCode()).isEqualTo("MC");
        assertThat(styleCaptor.getAllValues().get(0).getIsPrimary()).isTrue();
        assertThat(styleCaptor.getAllValues().get(1).getStyleCode()).isEqualTo("CR");
        assertThat(styleCaptor.getAllValues().get(1).getIsPrimary()).isFalse();
    }


    @Test
    void updateProduct_shouldUpdateProductLevel() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("MC");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setProductLevel("C");

        productQueryService.updateProduct("RSPU-TEST01", request);

        assertThat(rspu.getProductLevel()).isEqualTo("C");
    }

    @Test
    void updateProduct_shouldIgnoreNullFields() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("MC");
        rspu.setColorPrimaryName("原木色");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setWarrantyYears(5);

        productQueryService.updateProduct("RSPU-TEST01", request);

        assertThat(rspu.getPositioningLabel()).isEqualTo("MC");
        assertThat(rspu.getColorPrimaryName()).isEqualTo("原木色");
        assertThat(rspu.getWarrantyYears()).isEqualTo(5);
    }

    @Test
    void updateProduct_shouldThrowWhenNotFound() {
        when(rspuMapper.selectById(eq("RSPU-NOTEXIST"))).thenReturn(null);

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setColorPrimaryName("黑色");

        assertThatThrownBy(() -> productQueryService.updateProduct("RSPU-NOTEXIST", request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateProduct_shouldRejectInvalidPositioningLabel() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("MC");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(dictService.listByType("style")).thenReturn(styleDicts());

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setPositioningLabel("INVALID");

        assertThatThrownBy(() -> productQueryService.updateProduct("RSPU-TEST01", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("风格不存在");
    }

    @Test
    void updateProduct_shouldRejectInvalidSceneTag() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("MC");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(dictService.listByType("scene")).thenReturn(sceneDicts());

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setSceneTags(List.of("INVALID"));

        assertThatThrownBy(() -> productQueryService.updateProduct("RSPU-TEST01", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景标签不存在");
    }

    @Test
    void updateProduct_shouldNormalizeStyleCodeToUpperCase() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setPositioningLabel("OLD");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(dictService.listByType("style")).thenReturn(styleDicts());

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setPositioningLabel("mc");

        productQueryService.updateProduct("RSPU-TEST01", request);

        assertThat(rspu.getPositioningLabel()).isEqualTo("MC");
        verify(rspuStyleMapper).delete(any());
        verify(rspuStyleMapper).insert(any(RspuStyle.class));
    }

    @Test
    void deleteProduct_shouldSoftDeleteAndPublishEvent() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setStatus("active");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(rspuMapper.deleteById(eq("RSPU-TEST01"))).thenReturn(1);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        productQueryService.deleteProduct("RSPU-TEST01");

        verify(rspuMapper).deleteById("RSPU-TEST01");
        verify(auditLogService).logDelete(eq("rspu_master"), eq("RSPU-TEST01"), any(), eq("admin"));

        ArgumentCaptor<RspuDeletedEvent> eventCaptor = ArgumentCaptor.forClass(RspuDeletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRspuId()).isEqualTo("RSPU-TEST01");
        assertThat(eventCaptor.getValue().getImageIds()).isEmpty();
    }

    @Test
    void deleteProduct_shouldPublishEventWithImageIds() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setStatus("active");

        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-01");

        when(rspuMapper.selectById(eq("RSPU-TEST01"))).thenReturn(rspu);
        when(rspuMapper.deleteById(eq("RSPU-TEST01"))).thenReturn(1);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(image));

        productQueryService.deleteProduct("RSPU-TEST01");

        verify(rspuMapper).deleteById("RSPU-TEST01");

        ArgumentCaptor<RspuDeletedEvent> eventCaptor = ArgumentCaptor.forClass(RspuDeletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRspuId()).isEqualTo("RSPU-TEST01");
        assertThat(eventCaptor.getValue().getImageIds()).containsExactly("IMG-01");
    }

    @Test
    void deleteProduct_shouldThrowWhenNotFound() {
        when(rspuMapper.selectById(eq("RSPU-NOTEXIST"))).thenReturn(null);

        assertThatThrownBy(() -> productQueryService.deleteProduct("RSPU-NOTEXIST"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listProducts_viewModeOwn_shouldReturnOnlyOwnProducts() {
        authenticateFactoryAdmin("factory");
        when(userFactoryService.getFactoryCodesByUsername("factory")).thenReturn(List.of("F001"));

        ProductListRequest request = new ProductListRequest();
        request.setViewMode("own");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-OWN01");
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setReviewStatus("已确认");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getRspuId()).isEqualTo("RSPU-OWN01");
    }

    @Test
    void listProducts_viewModeFull_shouldHideCoveredProductsAndKeepOwn() {
        authenticateFactoryAdmin("factory");
        when(userFactoryService.getFactoryCodesByUsername("factory")).thenReturn(List.of("F001"));

        SysUser user = new SysUser();
        user.setUsername("factory");
        user.setViewFullCatalog(true);
        when(sysUserMapper.selectByUsername("factory")).thenReturn(user);

        ProductListRequest request = new ProductListRequest();
        request.setViewMode("full");
        request.setSize(10L);

        // 覆盖产品：品类 FS + 风格 MC + 材质 PE 命中能力
        RspuMaster covered = new RspuMaster();
        covered.setRspuId("RSPU-COVERED");
        covered.setCategoryCode("FS");
        covered.setPositioningLabel("MC");
        covered.setMaterialTags("[\"PE\"]");
        covered.setReviewStatus("已确认");

        // 未覆盖产品：风格不同
        RspuMaster uncovered = new RspuMaster();
        uncovered.setRspuId("RSPU-UNCOVERED");
        uncovered.setCategoryCode("FS");
        uncovered.setPositioningLabel("BA");
        uncovered.setMaterialTags("[\"PE\"]");
        uncovered.setReviewStatus("已确认");

        // 自己产品：即使被覆盖也保留
        RspuMaster own = new RspuMaster();
        own.setRspuId("RSPU-OWN");
        own.setCategoryCode("FS");
        own.setPositioningLabel("MC");
        own.setMaterialTags("[\"PE\"]");
        own.setReviewStatus("已确认");

        when(rspuMapper.selectList(any())).thenReturn(List.of(covered, uncovered, own));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(ownRsku("RSPU-OWN", "F001")));

        FactoryProductCapability capability = new FactoryProductCapability();
        capability.setFactoryCode("F001");
        capability.setCategoryCode("FS");
        capability.setStyleCode("MC");
        capability.setMaterialCode("PE");
        when(capabilityMapper.selectList(any())).thenReturn(List.of(capability));

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        List<String> ids = result.getRows().stream().map(ProductSummaryResponse::getRspuId).toList();
        assertThat(ids).containsExactlyInAnyOrder("RSPU-UNCOVERED", "RSPU-OWN");
        assertThat(ids).doesNotContain("RSPU-COVERED");
    }

    @Test
    void updateProduct_nonOwnerFactoryAdmin_shouldThrow() {
        authenticateFactoryAdmin("factory");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-OTHER");
        rspu.setPositioningLabel("MC");

        when(rspuMapper.selectById(eq("RSPU-OTHER"))).thenReturn(rspu);
        doThrow(new BusinessException("只能编辑自己工厂已报价的产品"))
            .when(dataScopeHelper).assertCanAccessRspu("RSPU-OTHER");

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setColorPrimaryName("黑色");

        assertThatThrownBy(() -> productQueryService.updateProduct("RSPU-OTHER", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能编辑自己工厂已报价的产品");
    }

    private void authenticateFactoryAdmin(String username) {
        SecurityContextHolder.clearContext();
        var user = User.withUsername(username).password("").roles("FACTORY_ADMIN").build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private com.rsdp.entity.RskuSupply ownRsku(String rspuId, String factoryCode) {
        com.rsdp.entity.RskuSupply rsku = new com.rsdp.entity.RskuSupply();
        rsku.setRspuId(rspuId);
        rsku.setFactoryCode(factoryCode);
        return rsku;
    }
}
