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
import static org.mockito.ArgumentMatchers.argThat;
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
    private com.rsdp.mapper.AuditLogMapper auditLogMapper;
    @Mock
    private RspuRelationMapper rspuRelationMapper;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private RspuPriceSummaryService rspuPriceSummaryService;
    @Mock
    private ProductQueryService productQueryService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private com.rsdp.service.RspuAssociationHelper associationHelper;

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
    }

    private RspuMaster sourceRspu() {
        RspuMaster r = new RspuMaster();
        r.setRspuId(SRC);
        r.setStatus("active");
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
        r.setSupplierConfigurationKey("BASE");
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
            // 2026-10-14 编码规则：迁移 RSKU 保留原编码，不再置空重发
            assertThat(result.get("reissuedRskuCodes")).isNull();

            // 双投影重算 + 副本软删复用 deleteProduct；不再触发编码补发
            verify(rspuPriceSummaryService).recalculate(SRC);
            verify(rspuPriceSummaryService).recalculate(TGT);
            verify(productQueryService).deleteProduct(SRC);
            // 引用改指（抽查关键几项）
            verify(rspuMergeMapper).repointSchemeItems(SRC, TGT);
            verify(rspuMergeMapper).repointFavorites(SRC, TGT);
            verify(rspuMergeMapper).repointFactoryMappings(SRC, TGT);
            // 风格并集：IT 新增 → 目标关联表重写（4.3 批②：经 helper 落库）
            verify(associationHelper).replaceStyles(eq(TGT), any(), eq("admin"), eq(true));
            // 场景无新增（LIVING 重复）→ 不重写
            verify(associationHelper, never()).replaceScenes(eq(TGT), any(), any(), eq(true));
            // 配对闭环（merged + dismissed 两次 update）
            verify(duplicateSuspectMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
        }
    }


    @Test
    void merge_shouldRepointSupplierProductRefs() {
        // 副本 3 条供应商身份：1 条与目标撞身份键（去重删除），2 条改指目标
        when(rspuMergeMapper.deleteDuplicateSupplierProductRefs(SRC, TGT)).thenReturn(1);
        when(rspuMergeMapper.repointSupplierProductRefs(SRC, TGT)).thenReturn(2);
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");

            Map<String, Object> result = mergeService.merge(request());

            // 先去重（撞 uk_supplier_product_ref 的副本记录物理删）再改指目标，顺序不可颠倒
            var order = org.mockito.Mockito.inOrder(rspuMergeMapper);
            order.verify(rspuMergeMapper).deleteDuplicateSupplierProductRefs(SRC, TGT);
            order.verify(rspuMergeMapper).repointSupplierProductRefs(SRC, TGT);
            assertThat(result.get("movedSupplierRefs")).isEqualTo(2);
            assertThat(result.get("removedDuplicateSupplierRefs")).isEqualTo(1);
        }
    }

    @Test
    void mergeVariants_shouldMigrateRskuAndImagesAndSoftDeleteSource() {
        RspuVariant source = variant("VAR-S", SRC, "M", "红", "WO");
        RspuVariant target = variant("VAR-T", SRC, "L", "红", "WO");
        when(rspuVariantMapper.selectById("VAR-S")).thenReturn(source);
        when(rspuVariantMapper.selectById("VAR-T")).thenReturn(target);
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(rsku("RSKU-S-1", SRC, "VAR-S", "A001", "c1")), List.of());
        when(imageAssetsMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");

            com.rsdp.dto.request.VariantMergeRequest req = new com.rsdp.dto.request.VariantMergeRequest();
            req.setSourceVariantId("VAR-S");
            req.setTargetVariantId("VAR-T");
            Map<String, Object> result = mergeService.mergeVariants(SRC, req);

            assertThat(result.get("migratedRskuCount")).isEqualTo(1);
            assertThat(result.get("movedImageCount")).isEqualTo(2);
            verify(rspuVariantMapper).deleteById("VAR-S");
            verify(rspuPriceSummaryService).recalculate(SRC);
        }
    }

    @Test
    void mergeVariants_shouldRejectUnresolvedRskuConflict() {
        RspuVariant source = variant("VAR-S", SRC, "M", "红", "WO");
        RspuVariant target = variant("VAR-T", SRC, "L", "红", "WO");
        when(rspuVariantMapper.selectById("VAR-S")).thenReturn(source);
        when(rspuVariantMapper.selectById("VAR-T")).thenReturn(target);
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(rsku("RSKU-S-1", SRC, "VAR-S", "A001", "c1")),
                List.of(rsku("RSKU-T-1", SRC, "VAR-T", "A001", "c2")));
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            com.rsdp.dto.request.VariantMergeRequest req = new com.rsdp.dto.request.VariantMergeRequest();
            req.setSourceVariantId("VAR-S");
            req.setTargetVariantId("VAR-T");
            assertThatThrownBy(() -> mergeService.mergeVariants(SRC, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冲突");
            verify(rspuVariantMapper, never()).deleteById(anyString());
        }
    }

    @Test
    void mergeVariants_shouldRejectCrossProduct() {
        RspuVariant source = variant("VAR-S", SRC, "M", "红", "WO");
        RspuVariant target = variant("VAR-T", TGT, "M", "红", "WO");
        when(rspuVariantMapper.selectById("VAR-S")).thenReturn(source);
        when(rspuVariantMapper.selectById("VAR-T")).thenReturn(target);
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            com.rsdp.dto.request.VariantMergeRequest req = new com.rsdp.dto.request.VariantMergeRequest();
            req.setSourceVariantId("VAR-S");
            req.setTargetVariantId("VAR-T");
            assertThatThrownBy(() -> mergeService.mergeVariants(SRC, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一产品");
        }
    }

    @Test
    void dismissSuspect_shouldMarkDismissed() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");
            when(duplicateSuspectMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

            mergeService.dismissSuspect(9L);

            verify(duplicateSuspectMapper).update(isNull(), any(UpdateWrapper.class));
        }
    }

    @Test
    void dismissSuspect_shouldRejectNonPlatformStaff() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);

            assertThatThrownBy(() -> mergeService.dismissSuspect(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台运营");
        }
    }

    @Test
    void listAllPendingSuspects_shouldReturnBothSidesInfo() {
        com.rsdp.entity.RspuDuplicateSuspect suspect = new com.rsdp.entity.RspuDuplicateSuspect();
        suspect.setSuspectId(1L);
        suspect.setRspuId(SRC);
        suspect.setMatchedRspuId(TGT);
        suspect.setSimilarity(java.math.BigDecimal.valueOf(0.97));
        when(duplicateSuspectMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(suspect));
        // 富信息版本走批量预取：产品/主图/价格投影均按集合查询
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(sourceRspu(), targetRspu()));

        List<Map<String, Object>> result = mergeService.listAllPendingSuspects();

        assertThat(result).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> sideA = (Map<String, Object>) result.get(0).get("sideA");
        @SuppressWarnings("unchecked")
        Map<String, Object> sideB = (Map<String, Object>) result.get(0).get("sideB");
        assertThat(sideA.get("rspuCode")).isEqualTo("FS-MC-072-M");
        assertThat(sideB.get("rspuCode")).isEqualTo("FS-MC-001-M");
    }

    @Test
    void rollbackMerge_shouldRejectNonPlatformStaff() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);

            assertThatThrownBy(() -> mergeService.rollbackMerge(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台运营");
        }
    }

    @Test
    void rollbackMerge_shouldRejectAlreadyRolledBack() {
        com.rsdp.entity.AuditLog entry = mergeAuditEntry();
        when(auditLogMapper.selectById(1L)).thenReturn(entry);
        when(rspuMergeMapper.countRollbacksForMerge(1L)).thenReturn(1L);
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            assertThatThrownBy(() -> mergeService.rollbackMerge(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被回滚");
        }
    }

    @Test
    void rollbackMerge_happyPath_shouldRestoreSourceAndRepointBack() {
        com.rsdp.entity.AuditLog entry = mergeAuditEntry();
        when(auditLogMapper.selectById(1L)).thenReturn(entry);
        when(rspuMergeMapper.countRollbacksForMerge(1L)).thenReturn(0L);
        RspuMaster deletedSource = sourceRspu();
        deletedSource.setDeletedAt(java.time.LocalDateTime.now());
        when(rspuMapper.selectAnyById(SRC)).thenReturn(deletedSource);
        RspuMaster target = targetRspu();
        when(rspuMapper.selectById(TGT)).thenReturn(target);
        when(rspuMergeMapper.restoreRspuRow(SRC)).thenReturn(1);
        RskuSupply migrated = rsku("RSKU-S-1", TGT, "VAR-T-1", "A001", "code");
        when(rskuSupplyMapper.selectById("RSKU-S-1")).thenReturn(migrated);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUsername()).thenReturn("admin");

            Map<String, Object> result = mergeService.rollbackMerge(1L);

            // 副本恢复 + 移动变体挂回 + 映射变体恢复 + RSKU 逆映射挂回 + 冲突双方报价恢复
            verify(rspuMergeMapper).restoreRspuRow(SRC);
            verify(rspuMergeMapper).repointVariantTo("VAR-S-MOVE", SRC);
            verify(rspuMergeMapper).restoreVariantRow("VAR-S-HIT");
            verify(rskuSupplyMapper).update(isNull(), any(UpdateWrapper.class));
            verify(rspuMergeMapper).restoreRskuRow("RSKU-DEL-T");
            verify(rspuMergeMapper).restoreRskuRow("RSKU-DEL-S");
            // 目标被覆盖字段还原（合并时 description 被副本填入）
            assertThat(target.getDescription()).isNull();
            // 回滚审计落记录（防重复回滚标记）
            verify(auditLogService).logUpdate(eq("rspu_master"), eq(TGT), isNull(),
                argThat(map -> "merge_rollback".equals(((Map<?, ?>) map).get("action"))), eq("admin"));
            assertThat(result.get("message").toString()).contains("取消合并");
        }
    }

    @Test
    void listMergeHistory_shouldExposeMergeDetailAndRollbackFlag() {
        RspuMergeMapper.MergeHistoryRow row = new RspuMergeMapper.MergeHistoryRow();
        row.setId(1L);
        row.setRecordId(TGT);
        row.setOperator("admin");
        row.setNewValueJson("{\"action\":\"merge\",\"sourceRspuId\":\"RSPU-SRC\"}");
        when(rspuMergeMapper.listMergeHistory()).thenReturn(List.of(row));
        when(rspuMergeMapper.countRollbacksForMerge(1L)).thenReturn(0L);

        List<Map<String, Object>> history = mergeService.listMergeHistory();

        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("sourceRspuId")).isEqualTo(SRC);
        assertThat(history.get(0).get("targetRspuId")).isEqualTo(TGT);
        assertThat(history.get(0).get("rolledBack")).isEqualTo(false);
        assertThat(history.get(0).get("operator")).isEqualTo("admin");
    }

    /** 合并审计记录测试桩（含回滚所需的迁移明细结构）。 */
    private com.rsdp.entity.AuditLog mergeAuditEntry() {
        com.rsdp.entity.AuditLog entry = new com.rsdp.entity.AuditLog();
        entry.setId(1L);
        entry.setRecordId(TGT);
        entry.setOperator("admin");
        Map<String, Object> detail = new java.util.HashMap<>();
        detail.put("action", "merge");
        detail.put("sourceRspuId", SRC);
        detail.put("sourceRspuCode", "FS-MC-072-M");
        detail.put("changedFields", List.of("description"));
        Map<String, Object> before = new java.util.HashMap<>();
        before.put("description", null);
        detail.put("targetFieldBefore", before);
        detail.put("movedVariantIds", List.of("VAR-S-MOVE"));
        detail.put("variantIdMap", Map.of("VAR-S-HIT", "VAR-T-1", "VAR-S-MOVE", "VAR-S-MOVE"));
        Map<String, Object> rsku = new java.util.HashMap<>();
        rsku.put("migratedRskuIds", List.of("RSKU-S-1"));
        rsku.put("conflictKeepTargetDeleted", List.of("RSKU-DEL-S"));
        rsku.put("conflictKeepSourceReplaced", List.of("RSKU-DEL-T"));
        detail.put("rsku", rsku);
        detail.put("movedImageIds", List.of());
        detail.put("supplierRefIdsBefore", List.of());
        detail.put("factoryMappingIdsBefore", List.of());
        detail.put("anchorRelationIdsBefore", List.of());
        detail.put("relatedRelationIdsBefore", List.of());
        entry.setNewValue(detail);
        return entry;
    }

    @Test
    void listSuspectClusters_shouldGroupConnectedPairs() {
        // 并查集连通分组：A-B、B-C 连成一组（3 成员），D-E 独立一组
        java.util.function.BiFunction<String, String, com.rsdp.entity.RspuDuplicateSuspect> pair = (a, b) -> {
            com.rsdp.entity.RspuDuplicateSuspect su = new com.rsdp.entity.RspuDuplicateSuspect();
            su.setRspuId(a);
            su.setMatchedRspuId(b);
            su.setSimilarity(java.math.BigDecimal.valueOf(0.97));
            return su;
        };
        when(duplicateSuspectMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(pair.apply("A", "B"), pair.apply("B", "C"), pair.apply("D", "E")));
        RspuMaster ra = new RspuMaster();
        ra.setRspuId("A");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(ra));

        List<Map<String, Object>> clusters = mergeService.listSuspectClusters();

        assertThat(clusters).hasSize(2);
        assertThat(clusters.stream().map(c -> c.get("memberCount")).toList())
            .containsExactlyInAnyOrder(3, 2);
    }

    @Test
    void preview_shouldExposeSupplierRefCount() {
        when(rspuMergeMapper.countSupplierProductRefs(SRC)).thenReturn(3L);
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            Map<String, Object> result = mergeService.preview(request());

            assertThat(result.get("supplierRefCount")).isEqualTo(3L);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void differentSupplierConfigurations_coexistInPreviewAndMerge(boolean preview) {
        RskuSupply source = sourceRsku();
        RskuSupply target = rsku("RSKU-T-1", TGT, "VAR-T-1", "A001", "target-code");
        source.setSupplierConfigurationKey("CODE:STANDARD");
        target.setSupplierConfigurationKey("CODE:PREMIUM");
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(source), List.of(target));
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            Map<String, Object> result = preview ? mergeService.preview(request()) : mergeService.merge(request());
            if (preview) {
                assertThat((List<?>) result.get("conflicts")).isEmpty();
            } else {
                assertThat(result.get("migratedCount")).isEqualTo(1);
            }
            verify(rskuSupplyMapper, never()).deleteById(anyString());
        }
    }

    @Test
    void movedVariant_defersOwnershipUntilLiveAndArchivedChildrenAreMigrated() {
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(
            List.of(rsku("RSKU-MOVE", SRC, "VAR-S-MOVE", "A001", "old")), List.of());
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(image("IMG-MOVE", "VAR-S-MOVE")));
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            mergeService.merge(request());
            var order = org.mockito.Mockito.inOrder(rspuMergeMapper, rspuVariantMapper,
                rskuSupplyMapper, imageAssetsMapper, productQueryService);
            order.verify(rspuMergeMapper).deferVariantOwnershipConstraints();
            order.verify(rspuVariantMapper).updateById(any(RspuVariant.class));
            order.verify(rskuSupplyMapper).update(isNull(), any(UpdateWrapper.class));
            order.verify(imageAssetsMapper).updateById(any(ImageAssets.class));
            order.verify(rspuMergeMapper).repointArchivedVariantRskus(SRC, TGT, "VAR-S-MOVE");
            order.verify(rspuMergeMapper).repointArchivedVariantImages(SRC, TGT, "VAR-S-MOVE");
            order.verify(rspuMergeMapper).checkVariantOwnershipConstraints();
            order.verify(productQueryService).deleteProduct(SRC);
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
    @SuppressWarnings("unchecked")
    void preview_shouldReturnFieldDiffsAndConflictsWithoutWrites() {
        // 目标已有同工厂同变体报价 → 预览返回冲突但不报错、不写库
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(
                List.of(rsku("RSKU-S-1", SRC, "VAR-S-HIT", "A001", "old-code")),
                List.of(rsku("RSKU-T-1", TGT, "VAR-T-1", "A001", "tgt-code")));
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            Map<String, Object> result = mergeService.preview(request());

            List<Map<String, Object>> conflicts = (List<Map<String, Object>>) result.get("conflicts");
            assertThat(conflicts).hasSize(1);
            assertThat(conflicts.get(0).get("rskuId")).isEqualTo("RSKU-S-1");
            assertThat(conflicts.get(0).get("conflictRskuId")).isEqualTo("RSKU-T-1");
            List<Map<String, Object>> fieldDiffs = (List<Map<String, Object>>) result.get("fieldDiffs");
            assertThat(fieldDiffs).hasSize(11);
            // description：源有值目标空缺 → willFill=true；productName：双方有值 → willFill=false
            assertThat(fieldDiffs.stream().filter(d -> "description".equals(d.get("field"))).findFirst().orElseThrow()
                .get("willFill")).isEqualTo(true);
            assertThat(fieldDiffs.stream().filter(d -> "productName".equals(d.get("field"))).findFirst().orElseThrow()
                .get("willFill")).isEqualTo(false);
            // 只读：无任何写入
            verify(rskuSupplyMapper, never()).update(isNull(), any(UpdateWrapper.class));
            verify(rskuSupplyMapper, never()).deleteById(anyString());
            verify(rspuVariantMapper, never()).updateById(any(RspuVariant.class));
            verify(productQueryService, never()).deleteProduct(anyString());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void preview_noConflict_shouldReturnEmptyConflicts() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            Map<String, Object> result = mergeService.preview(request());

            assertThat((List<Map<String, Object>>) result.get("conflicts")).isEmpty();
            assertThat(result.get("rskuCount")).isEqualTo(1);
            assertThat(result.get("movedVariantCount")).isEqualTo(1);
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
