package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RspuMergeRequest;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuDuplicateSuspectMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuMergeMapper;
import com.rsdp.mapper.RspuRelationMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RspuMergeService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RspuMergeServiceTest {

    @Mock
    private RspuMapper rspuMapper;
    @Mock
    private RspuStyleMapper rspuStyleMapper;
    @Mock
    private RspuSceneMapper rspuSceneMapper;
    @Mock
    private RspuVariantMapper rspuVariantMapper;
    @Mock
    private RskuSupplyMapper rskuSupplyMapper;
    @Mock
    private ImageAssetsMapper imageAssetsMapper;
    @Mock
    private RspuDuplicateSuspectMapper duplicateSuspectMapper;
    @Mock
    private RspuMergeMapper rspuMergeMapper;
    @Mock
    private RspuRelationMapper rspuRelationMapper;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private RspuPriceSummaryService rspuPriceSummaryService;
    @Mock
    private RskuCodeService rskuCodeService;
    @Mock
    private ProductQueryService productQueryService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RspuMergeService mergeService;

    private static final String SRC = "RSPU-SRC";
    private static final String TGT = "RSPU-TGT";

    @BeforeEach
    void setUp() {
        lenient().when(rspuMapper.selectById(SRC)).thenReturn(sourceRspu());
        lenient().when(rspuMapper.selectById(TGT)).thenReturn(targetRspu());
        // 变体：目标一条（M/红/木），副本两条（一条同 key 命中、一条新属性改挂）
        lenient().when(rspuVariantMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(targetVariant()), List.of(sourceVariantHit(), sourceVariantMove()));
        // RSKU：副本一条无冲突报价
        lenient().when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(sourceRsku()), List.of(targetRsku()));
        // 风格/场景：目标 MC/LIVING，副本 IT（并集新增）/LIVING（重复跳过）
        lenient().when(rspuStyleMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(style(TGT, "MC", true)), List.of(style(SRC, "IT", true)));
        lenient().when(rspuSceneMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(scene(TGT, "LIVING")), List.of(scene(SRC, "LIVING")));
        lenient().when(imageAssetsMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(image("IMG-1", "VAR-S-HIT")));
        lenient().when(rskuCodeService.backfillCodesByRspu(TGT)).thenReturn(1);
    }

    private RspuMaster sourceRspu() {
        RspuMaster r = new RspuMaster();
        r.setRspuId(SRC);
        r.setStatus("active");
        r.setReviewStatus("存疑");
        r.setRspuCode("FS-MC-072-M");
        r.setProductName("副本名称");
        r.setDescription("副本描述");
        r.setRetailPrice(new BigDecimal("199"));
        return r;
    }

    private RspuMaster targetRspu() {
        RspuMaster r = new RspuMaster();
        r.setRspuId(TGT);
        r.setStatus("active");
        r.setReviewStatus("待复核");
        r.setRspuCode("FS-MC-001-M");
        r.setProductName("目标名称");
        return r;
    }

    private RspuVariant variant(String id, String rspuId, String size, String color, String material) {
        RspuVariant v = new RspuVariant();
        v.setVariantId(id);
        v.setRspuId(rspuId);
        v.setSizeCode(size);
        v.setColorText(color);
        v.setMaterialCode(material);
        return v;
    }

    private RspuVariant targetVariant() {
        return variant("VAR-T-1", TGT, "M", "红", "WO");
    }

    private RspuVariant sourceVariantHit() {
        return variant("VAR-S-HIT", SRC, "M", "红", "WO");
    }

    private RspuVariant sourceVariantMove() {
        return variant("VAR-S-MOVE", SRC, "L", "蓝", "MT");
    }

    private RskuSupply rsku(String id, String rspuId, String variantId, String factory, String code) {
        RskuSupply r = new RskuSupply();
        r.setRskuId(id);
        r.setRspuId(rspuId);
        r.setVariantId(variantId);
        r.setFactoryCode(factory);
        r.setRskuCode(code);
        return r;
    }

    private RskuSupply sourceRsku() {
        return rsku("RSKU-S-1", SRC, "VAR-S-HIT", "A001", "FS-MC-072-M-A001-WO-001");
    }

    private RskuSupply targetRsku() {
        return rsku("RSKU-T-1", TGT, "VAR-T-1", "B002", "FS-MC-001-M-B002-WO-001");
    }

    private RspuStyle style(String rspuId, String code, boolean primary) {
        RspuStyle s = new RspuStyle();
        s.setRspuId(rspuId);
        s.setStyleCode(code);
        s.setIsPrimary(primary);
        return s;
    }

    private RspuScene scene(String rspuId, String code) {
        RspuScene s = new RspuScene();
        s.setRspuId(rspuId);
        s.setSceneCode(code);
        return s;
    }

    private ImageAssets image(String id, String variantId) {
        ImageAssets i = new ImageAssets();
        i.setImageId(id);
        i.setRspuId(RspuMergeServiceTest.SRC);
        i.setVariantId(variantId);
        return i;
    }

    private RspuMergeRequest request() {
        RspuMergeRequest req = new RspuMergeRequest();
        req.setSourceRspuId(SRC);
        req.setTargetRspuId(TGT);
        return req;
    }

    @Test
    void merge_shouldRejectNonPlatformStaff() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);

            assertThatThrownBy(() -> mergeService.merge(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台运营");
        }
    }

    @Test
    void merge_shouldRejectSameSourceAndTarget() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            RspuMergeRequest req = request();
            req.setTargetRspuId(SRC);

            assertThatThrownBy(() -> mergeService.merge(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一个产品");
        }
    }

    @Test
    void merge_shouldRejectMissingTarget() {
        when(rspuMapper.selectById(TGT)).thenReturn(null);
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            assertThatThrownBy(() -> mergeService.merge(request()))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void merge_shouldRejectProcessingTarget() {
        RspuMaster processing = targetRspu();
        processing.setStatus("processing");
        when(rspuMapper.selectById(TGT)).thenReturn(processing);
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            assertThatThrownBy(() -> mergeService.merge(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("识别中");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void merge_happyPath_shouldMigrateAndSoftDeleteSource() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");

            Map<String, Object> result = mergeService.merge(request());

            // 字段归并：productName 目标已有不覆盖；description/retailPrice 目标空缺补齐
            assertThat((List<String>) result.get("changedFields"))
                .contains("description", "retailPrice")
                .doesNotContain("productName");
            // 变体：同 key 命中映射 + 新属性改挂 1 条
            assertThat(result.get("movedVariantCount")).isEqualTo(1);
            // RSKU：迁移 1 条且编码置空（待重发），variant 指向目标命中变体
            assertThat(result.get("migratedCount")).isEqualTo(1);
            // 图片改挂 1 张
            assertThat(result.get("movedImageCount")).isEqualTo(1);
            assertThat(result.get("reissuedRskuCodes")).isEqualTo(1);

            // 双投影重算 + 编码补发 + 副本软删复用 deleteProduct
            verify(rspuPriceSummaryService).recalculate(SRC);
            verify(rspuPriceSummaryService).recalculate(TGT);
            verify(rskuCodeService).backfillCodesByRspu(TGT);
            verify(productQueryService).deleteProduct(SRC);
            // 引用改指（抽查关键几项）
            verify(rspuMergeMapper).repointSchemeItems(SRC, TGT);
            verify(rspuMergeMapper).repointFavorites(SRC, TGT);
            verify(rspuMergeMapper).repointFactoryMappings(SRC, TGT);
            // 风格并集：IT 新增 → 目标关联表重写
            verify(rspuStyleMapper).delete(any(QueryWrapper.class));
            // 场景无新增（LIVING 重复）→ 不重写
            verify(rspuSceneMapper, never()).delete(any(QueryWrapper.class));
            // 配对闭环（merged + dismissed 两次 update）
            verify(duplicateSuspectMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
        }
    }

    @Test
    void merge_shouldRejectUnresolvedRskuConflict() {
        // 目标已有同工厂同变体报价 → 冲突且未提供裁决
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(
                List.of(rsku("RSKU-S-1", SRC, "VAR-S-HIT", "A001", "old-code")),
                List.of(rsku("RSKU-T-1", TGT, "VAR-T-1", "A001", "tgt-code")));
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");

            assertThatThrownBy(() -> mergeService.merge(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冲突")
                .hasMessageContaining("RSKU-S-1");
            // 整体回滚语义：不写任何 RSKU
            verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
            verify(rskuSupplyMapper, never()).update(isNull(), any(UpdateWrapper.class));
            verify(rskuSupplyMapper, never()).deleteById(anyString());
            verify(productQueryService, never()).deleteProduct(anyString());
        }
    }

    @Test
    void merge_conflictKeepTarget_shouldSoftDeleteSourceRsku() {
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(
                List.of(rsku("RSKU-S-1", SRC, "VAR-S-HIT", "A001", "old-code")),
                List.of(rsku("RSKU-T-1", TGT, "VAR-T-1", "A001", "tgt-code")));
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");
            RspuMergeRequest req = request();
            req.setRskuConflictResolutions(Map.of("RSKU-S-1", "keepTarget"));

            Map<String, Object> result = mergeService.merge(req);

            verify(rskuSupplyMapper).deleteById("RSKU-S-1");
            verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
            verify(rskuSupplyMapper, never()).update(isNull(), any(UpdateWrapper.class));
            assertThat(result.get("migratedCount")).isEqualTo(0);
            assertThat((List<String>) result.get("conflictKeepTargetDeleted")).containsExactly("RSKU-S-1");
        }
    }

    @Test
    void merge_conflictKeepSource_shouldReplaceTargetRskuAndMigrate() {
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(
                List.of(rsku("RSKU-S-1", SRC, "VAR-S-HIT", "A001", "old-code")),
                List.of(rsku("RSKU-T-1", TGT, "VAR-T-1", "A001", "tgt-code")));
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");
            RspuMergeRequest req = request();
            req.setRskuConflictResolutions(Map.of("RSKU-S-1", "keepSource"));

            Map<String, Object> result = mergeService.merge(req);

            verify(rskuSupplyMapper).deleteById("RSKU-T-1");
            verify(rskuSupplyMapper).update(isNull(), any(UpdateWrapper.class));
            assertThat(result.get("migratedCount")).isEqualTo(1);
            assertThat((List<String>) result.get("conflictKeepSourceReplaced")).containsExactly("RSKU-T-1");
        }
    }

    @Test
    void merge_shouldRejectFieldOverrideOutsideWhitelist() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            RspuMergeRequest req = request();
            req.setTakeSourceFields(List.of("rspuCode"));

            assertThatThrownBy(() -> mergeService.merge(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void merge_takeSourceFields_shouldOverrideTargetValue() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");
            RspuMergeRequest req = request();
            req.setTakeSourceFields(List.of("productName"));

            Map<String, Object> result = mergeService.merge(req);

            assertThat((List<String>) result.get("changedFields")).contains("productName");
        }
    }

    @Test
    void listPendingSuspects_shouldEnrichMatchedProduct() {
        com.rsdp.entity.RspuDuplicateSuspect suspect = new com.rsdp.entity.RspuDuplicateSuspect();
        suspect.setSuspectId(7L);
        suspect.setRspuId(SRC);
        suspect.setMatchedRspuId(TGT);
        suspect.setSimilarity(new BigDecimal("0.9700"));
        suspect.setStatus("pending");
        when(duplicateSuspectMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(suspect));

        List<Map<String, Object>> result = mergeService.listPendingSuspects(SRC);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("matchedRspuCode")).isEqualTo("FS-MC-001-M");
        assertThat(result.get(0).get("matchedProductName")).isEqualTo("目标名称");
    }
}
