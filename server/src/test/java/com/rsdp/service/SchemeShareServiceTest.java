package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.response.SchemeShareResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * {@link SchemeShareService} 单元测试（方案级分享，V42）。
 */
@ExtendWith(MockitoExtension.class)
class SchemeShareServiceTest {

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private SchemeItemMapper schemeItemMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private RspuSceneMapper rspuSceneMapper;

    @Mock
    private CategoryDictMapper categoryDictMapper;

    @Mock
    private ProjectShareService projectShareService;

    @Mock
    private SchemeSalePriceService schemeSalePriceService;

    @InjectMocks
    private SchemeShareService schemeShareService;

    private Scheme sharedScheme() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setSchemeName("客厅方案");
        scheme.setShareEnabled(true);
        return scheme;
    }

    private SchemeItem baseItem() {
        SchemeItem item = new SchemeItem();
        item.setSchemeItemId(1L);
        item.setSchemeId("SCHEME-1");
        item.setRspuId("RSPU-001");
        item.setRskuId("RSKU-001");
        item.setFactoryCode("F001");
        item.setQuantity(2);
        item.setSortOrder(1);
        return item;
    }

    /** 装配分享视图查询夹具：1 个明细 + 产品 + 主图 + 售价，空间标签与场景字典由用例自行 stub。 */
    private void stubShareFixtures(Scheme scheme, SchemeItem item) {
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);
        when(schemeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(item));
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("北欧布艺沙发");
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rspu));
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(image));
        org.mockito.Mockito.lenient().when(schemeSalePriceService.salePriceOf(any(), any()))
            .thenReturn(new java.math.BigDecimal("1999.00"));
    }

    private CategoryDict sceneDict(String code, String name) {
        CategoryDict dict = new CategoryDict();
        dict.setDictCode(code);
        dict.setDictName(name);
        return dict;
    }

    @Test
    void getSharedSchemeShouldReturnPublicView() {
        Scheme scheme = sharedScheme();
        SchemeItem item = baseItem();
        item.setSpaceTag("LIVING");
        stubShareFixtures(scheme, item);
        when(rspuSceneMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(categoryDictMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(sceneDict("LIVING", "客厅")));

        SchemeShareResponse response = schemeShareService.getSharedScheme("SCHEME-1");

        assertThat(response.getSchemeName()).isEqualTo("客厅方案");
        assertThat(response.getItems()).hasSize(1);
        SchemeShareResponse.ShareItem shareItem = response.getItems().get(0);
        assertThat(shareItem.getRspuId()).isEqualTo("RSPU-001");
        assertThat(shareItem.getProductName()).isEqualTo("北欧布艺沙发");
        assertThat(shareItem.getImageId()).isEqualTo("IMG-001");
        assertThat(shareItem.getQuantity()).isEqualTo(2);
        assertThat(shareItem.getSalePrice()).isEqualByComparingTo("1999.00");
        assertThat(shareItem.getSpaceTagName()).isEqualTo("客厅");
        assertThat(shareItem.getSortOrder()).isEqualTo(1);
    }

    @Test
    void getSharedSchemeShouldFallbackToProductSceneWhenNoOverride() {
        // 无覆盖码：回退产品 rspu_scene 首场景码
        Scheme scheme = sharedScheme();
        stubShareFixtures(scheme, baseItem());
        RspuScene scene = new RspuScene();
        scene.setRspuId("RSPU-001");
        scene.setSceneCode("BEDROOM");
        when(rspuSceneMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(scene));
        when(categoryDictMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(sceneDict("BEDROOM", "卧室")));

        SchemeShareResponse response = schemeShareService.getSharedScheme("SCHEME-1");

        assertThat(response.getItems().get(0).getSpaceTagName()).isEqualTo("卧室");
    }

    @Test
    void getSharedSchemeShouldFallbackToRawCodeWhenDictDeleted() {
        // 码已删：spaceTagName 原样返回码
        Scheme scheme = sharedScheme();
        SchemeItem item = baseItem();
        item.setSpaceTag("OLDSPACE");
        stubShareFixtures(scheme, item);
        when(rspuSceneMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(categoryDictMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        SchemeShareResponse response = schemeShareService.getSharedScheme("SCHEME-1");

        assertThat(response.getItems().get(0).getSpaceTagName()).isEqualTo("OLDSPACE");
    }

    @Test
    void getSharedSchemeShouldRejectWhenShareDisabled() {
        Scheme scheme = sharedScheme();
        scheme.setShareEnabled(false);
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);

        assertThatThrownBy(() -> schemeShareService.getSharedScheme("SCHEME-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("未开启分享");
    }

    @Test
    void getSharedSchemeShouldRejectWhenExpired() {
        Scheme scheme = sharedScheme();
        scheme.setShareExpireAt(LocalDateTime.now().minusDays(1));
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);

        assertThatThrownBy(() -> schemeShareService.getSharedScheme("SCHEME-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("已过期");
    }

    @Test
    void getSharedProjectSchemeShouldReturnViewWhenProjectShareValid() {
        Scheme scheme = sharedScheme();
        scheme.setProjectId("PROJ-1");
        stubShareFixtures(scheme, baseItem());
        when(rspuSceneMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        SchemeShareResponse response = schemeShareService.getSharedProjectScheme("PROJ-1", "SCHEME-1");

        assertThat(response.getSchemeName()).isEqualTo("客厅方案");
        assertThat(response.getItems()).hasSize(1);
    }

    @Test
    void getSharedProjectSchemeShouldRejectWhenProjectShareInvalid() {
        // 项目分享未开启/已过期：直接拒绝
        doThrow(new ResourceNotFoundException("未开启分享或该页面不存在"))
            .when(projectShareService).getValidSharedProject("PROJ-1");

        assertThatThrownBy(() -> schemeShareService.getSharedProjectScheme("PROJ-1", "SCHEME-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("未开启分享");
    }

    @Test
    void getSharedProjectSchemeShouldRejectWhenSchemeNotInProject() {
        // 方案不属于该项目：拒绝
        Scheme scheme = sharedScheme();
        scheme.setProjectId("PROJ-OTHER");
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);

        assertThatThrownBy(() -> schemeShareService.getSharedProjectScheme("PROJ-1", "SCHEME-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("不属于该项目");
    }

    @Test
    void shareResponseShouldNotLeakSensitiveFields() throws Exception {
        // 严格白名单：公开响应序列化后不含工厂/成本/RSKU 等敏感字段（标准售价 salePrice 为对客价格，允许出现）
        Scheme scheme = sharedScheme();
        stubShareFixtures(scheme, baseItem());
        when(rspuSceneMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        SchemeShareResponse response = schemeShareService.getSharedScheme("SCHEME-1");
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).doesNotContain("factoryCode");
        assertThat(json).doesNotContain("factoryPrice");
        assertThat(json).doesNotContain("factoryName");
        assertThat(json).doesNotContain("rskuId");
        assertThat(json).doesNotContain("factory");
        assertThat(json).doesNotContain("costPrice");
        assertThat(json).contains("salePrice");
    }
}
