package com.rsdp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.request.ProductUpdateRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductQueryService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

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
    private AuditLogService auditLogService;

    @Mock
    private DictService dictService;

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
        when(imageAssetsMapper.selectOne(any())).thenReturn(null);

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

        RspuStyle style = new RspuStyle();
        style.setRspuId("RSPU-TEST01");
        style.setStyleCode("MC");
        when(rspuStyleMapper.selectList(any())).thenReturn(List.of(style));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setStatus("active");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectOne(any())).thenReturn(null);

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
        when(imageAssetsMapper.selectOne(any())).thenReturn(null);

        PageResult<ProductSummaryResponse> result = productQueryService.listProducts(request);

        assertThat(result.getRows()).hasSize(1);
    }

    @Test
    void listProducts_shouldFilterBySceneCode() {
        ProductListRequest request = new ProductListRequest();
        request.setPage(1L);
        request.setSize(10L);
        request.setSceneCode("LIVING");

        RspuScene scene = new RspuScene();
        scene.setRspuId("RSPU-TEST01");
        scene.setSceneCode("LIVING");
        when(rspuSceneMapper.selectList(any())).thenReturn(List.of(scene));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setStatus("active");

        Page<RspuMaster> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(rspu));

        when(rspuMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(imageAssetsMapper.selectOne(any())).thenReturn(null);

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

        ProductDetailResponse detail = productQueryService.getProductDetail("RSPU-TEST01");

        assertThat(detail.getRspu()).isNotNull();
        assertThat(detail.getImages()).isEmpty();
        assertThat(detail.getRecognitions()).isEmpty();
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
}
