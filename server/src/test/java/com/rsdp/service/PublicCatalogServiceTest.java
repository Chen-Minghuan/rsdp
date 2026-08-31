package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.response.PublicCategoryResponse;
import com.rsdp.dto.response.PublicProductDetailResponse;
import com.rsdp.dto.response.PublicProductItemResponse;
import com.rsdp.dto.response.PublicSceneResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PublicCatalogService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PublicCatalogServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuVariantMapper rspuVariantMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private DictService dictService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PublicCatalogService publicCatalogService;

    private RspuMaster sampleRspu() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setRspuCode("SF-WJ-002-L");
        rspu.setProductName("云朵三人位布艺沙发");
        rspu.setCategoryCode("SF");
        rspu.setCategoryPath("家装家具/客厅/沙发");
        rspu.setPositioningLabel("现代简约");
        rspu.setColorPrimaryName("米白");
        rspu.setMaterialTags("[\"布艺\",\"实木\"]");
        rspu.setRetailPrice(new BigDecimal("4680.00"));
        rspu.setStatus("active");
        return rspu;
    }

    @Test
    void listProducts_shouldAssembleImagesAndVariantCount() {
        Page<RspuMaster> page = new Page<>(1, 12);
        page.setRecords(List.of(sampleRspu()));
        page.setTotal(1);
        when(rspuMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        ImageAssets primary = new ImageAssets();
        primary.setRspuId("RSPU-1");
        primary.setImageId("IMG-1");
        when(imageAssetsMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(primary));

        Map<String, Object> countRow = new HashMap<>();
        countRow.put("rspu_id", "RSPU-1");
        countRow.put("cnt", 3L);
        when(rspuVariantMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of(countRow));

        PageResult<PublicProductItemResponse> result = publicCatalogService.listProducts(
            1, 12, null, null, null, null, null, null, "newest");

        assertThat(result.getTotal()).isEqualTo(1);
        PublicProductItemResponse item = result.getRows().get(0);
        assertThat(item.getRspuCode()).isEqualTo("SF-WJ-002-L");
        assertThat(item.getPrimaryImageUrl()).isEqualTo("/api/v1/images/IMG-1");
        assertThat(item.getVariantCount()).isEqualTo(3);
        assertThat(item.getMaterialTags()).containsExactly("布艺", "实木");
        assertThat(item.getRetailPrice()).isEqualByComparingTo("4680.00");
    }

    @Test
    void listProducts_emptyPage_shouldSkipBatchQueries() {
        Page<RspuMaster> page = new Page<>(1, 12);
        page.setRecords(List.of());
        page.setTotal(0);
        when(rspuMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        PageResult<PublicProductItemResponse> result = publicCatalogService.listProducts(
            1, 12, "SF", "3", "米白", "布艺", new BigDecimal("1000"), new BigDecimal("5000"), "price_asc");

        assertThat(result.getTotal()).isEqualTo(0);
        assertThat(result.getRows()).isEmpty();
    }

    @Test
    void listScenes_shouldAttachRepresentImage() {
        CategoryDict scene = new CategoryDict();
        scene.setDictType("scene");
        scene.setDictCode("LIVING");
        scene.setDictName("客厅");
        when(dictService.listByType("scene")).thenReturn(List.of(scene));

        ImageAssets image = new ImageAssets();
        image.setRspuId("RSPU-1");
        image.setImageId("IMG-7");
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(image));

        List<PublicSceneResponse> scenes = publicCatalogService.listScenes();

        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0).getSceneCode()).isEqualTo("LIVING");
        assertThat(scenes.get(0).getImageUrl()).isEqualTo("/api/v1/images/IMG-7");
    }

    @Test
    void listScenes_manualCover_shouldPreferDictImage() {
        CategoryDict scene = new CategoryDict();
        scene.setDictType("scene");
        scene.setDictCode("LIVING");
        scene.setDictName("客厅");
        scene.setImageId("IMG-COVER");
        when(dictService.listByType("scene")).thenReturn(List.of(scene));

        List<PublicSceneResponse> scenes = publicCatalogService.listScenes();

        // 手配封面优先，不再查询产品主图兜底
        assertThat(scenes.get(0).getImageUrl()).isEqualTo("/api/v1/images/IMG-COVER");
        verifyNoInteractions(imageAssetsMapper);
    }

    @Test
    void listScenes_noManualCover_shouldFallbackToProductImage() {
        CategoryDict scene = new CategoryDict();
        scene.setDictType("scene");
        scene.setDictCode("DINING");
        scene.setDictName("餐厅");
        when(dictService.listByType("scene")).thenReturn(List.of(scene));

        ImageAssets image = new ImageAssets();
        image.setRspuId("RSPU-2");
        image.setImageId("IMG-8");
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(image));

        List<PublicSceneResponse> scenes = publicCatalogService.listScenes();

        assertThat(scenes.get(0).getImageUrl()).isEqualTo("/api/v1/images/IMG-8");
    }

    @Test
    void listScenes_noImage_shouldReturnNullImageUrl() {
        CategoryDict scene = new CategoryDict();
        scene.setDictType("scene");
        scene.setDictCode("STUDY");
        scene.setDictName("书房");
        when(dictService.listByType("scene")).thenReturn(List.of(scene));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        List<PublicSceneResponse> scenes = publicCatalogService.listScenes();

        assertThat(scenes.get(0).getImageUrl()).isNull();
    }

    @Test
    void listCategories_shouldBuildTwoLevelTree() {
        CategoryDict root = new CategoryDict();
        root.setDictType("category");
        root.setDictCode("SF");
        root.setDictName("沙发");
        CategoryDict child = new CategoryDict();
        child.setDictType("category");
        child.setDictCode("SF-3P");
        child.setDictName("三人位沙发");
        child.setParentCode("SF");
        when(dictService.listByType("category")).thenReturn(List.of(root, child));

        List<PublicCategoryResponse> tree = publicCatalogService.listCategories();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getDictCode()).isEqualTo("SF");
        assertThat(tree.get(0).getChildren()).hasSize(1);
        assertThat(tree.get(0).getChildren().get(0).getDictCode()).isEqualTo("SF-3P");
    }

    @Test
    void listCategories_flatSeed_shouldReturnAllAsRoots() {
        CategoryDict sf = new CategoryDict();
        sf.setDictType("category");
        sf.setDictCode("SF");
        sf.setDictName("沙发");
        CategoryDict tb = new CategoryDict();
        tb.setDictType("category");
        tb.setDictCode("TB");
        tb.setDictName("茶几");
        when(dictService.listByType("category")).thenReturn(List.of(sf, tb));

        List<PublicCategoryResponse> tree = publicCatalogService.listCategories();

        assertThat(tree).hasSize(2);
        assertThat(tree).allMatch(node -> node.getChildren().isEmpty());
    }

    @Test
    void getProductDetail_shouldAssembleImagesAndVariants() throws Exception {
        RspuMaster rspu = sampleRspu();
        rspu.setDescription("三人位布艺沙发，羽绒填充");
        rspu.setFabricTags("[\"LI\"]");
        rspu.setWarrantyYears(3);
        rspu.setSixDimTags("{\"A\":\"宽厚扶手\"}");
        rspu.setKeySpecs("{\"框架\":\"实木\"}");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);

        ImageAssets primary = new ImageAssets();
        primary.setRspuId("RSPU-1");
        primary.setImageId("IMG-1");
        primary.setPrimary(true);
        ImageAssets detail = new ImageAssets();
        detail.setRspuId("RSPU-1");
        detail.setImageId("IMG-2");
        detail.setPrimary(false);
        detail.setVariantId("VAR-1");
        when(imageAssetsMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(primary, detail));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-1");
        variant.setDisplayName("585*580*750");
        variant.setSizeText("585*580*750");
        variant.setDimensions("{\"w\":585,\"d\":580,\"h\":750,\"unit\":\"mm\"}");
        variant.setMaterialText("实木");
        when(rspuVariantMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(variant));

        PublicProductDetailResponse result = publicCatalogService.getProductDetail("RSPU-1");

        assertThat(result.getRspuCode()).isEqualTo("SF-WJ-002-L");
        assertThat(result.getDescription()).isEqualTo("三人位布艺沙发，羽绒填充");
        assertThat(result.getFabricTags()).containsExactly("LI");
        assertThat(result.getSixDimTags()).containsEntry("A", "宽厚扶手");
        assertThat(result.getKeySpecs()).containsEntry("框架", "实木");
        assertThat(result.getRetailPrice()).isEqualByComparingTo("4680.00");

        assertThat(result.getImages()).hasSize(2);
        assertThat(result.getImages().get(0).getUrl()).isEqualTo("/api/v1/images/IMG-1");
        assertThat(result.getImages().get(0).getPrimary()).isTrue();
        assertThat(result.getImages().get(1).getVariantId()).isEqualTo("VAR-1");

        assertThat(result.getVariants()).hasSize(1);
        assertThat(result.getVariants().get(0).getDisplayName()).isEqualTo("585*580*750");
        assertThat(result.getVariants().get(0).getDimensions())
            .containsEntry("w", 585)
            .containsEntry("unit", "mm");

        // 脱敏红线：序列化结果绝不含工厂/RSKU/出厂价字段
        String json = objectMapper.writeValueAsString(result);
        assertThat(json).doesNotContain("factory", "factoryPrice", "rsku", "moq", "leadTimeDays");
    }

    @Test
    void getProductDetail_inactive_shouldThrowNotFound() {
        RspuMaster rspu = sampleRspu();
        rspu.setStatus("inactive");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> publicCatalogService.getProductDetail("RSPU-1"))
            .isInstanceOf(com.rsdp.exception.ResourceNotFoundException.class)
            .hasMessageContaining("商品不存在或已下架");
        verifyNoInteractions(imageAssetsMapper, rspuVariantMapper);
    }

    @Test
    void getProductDetail_notFound_shouldThrowNotFound() {
        when(rspuMapper.selectById("RSPU-X")).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> publicCatalogService.getProductDetail("RSPU-X"))
            .isInstanceOf(com.rsdp.exception.ResourceNotFoundException.class);
    }
}
