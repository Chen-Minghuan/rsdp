package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.response.PublicCategoryResponse;
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
        ImageAssets scene = new ImageAssets();
        scene.setRspuId("RSPU-1");
        scene.setImageId("IMG-2");
        // 第一次调用取主图，第二次取场景图
        when(imageAssetsMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(primary))
            .thenReturn(List.of(scene));

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
        assertThat(item.getSceneImageUrl()).isEqualTo("/api/v1/images/IMG-2");
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
}
