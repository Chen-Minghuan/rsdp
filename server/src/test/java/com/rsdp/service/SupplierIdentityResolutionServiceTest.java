package com.rsdp.service;

import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.RspuFactoryMapping;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.SupplierProductRef;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.RspuFactoryMappingMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.SupplierProductRefMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SupplierIdentityResolutionService} 单元测试（设计文档 v3.3 §9 Phase 3 / §77.1）。
 *
 * <p>覆盖：ref 优先命中、归一等价输入命中 ref、受限 legacy fallback 命中
 * （唯一 + 工厂验证）、fallback 拒绝（多歧义 / 候选不属于该工厂 / 工厂不存在）、
 * 无工厂上下文禁止裸查询（代码上无入口）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierIdentityResolutionServiceTest {

    @Mock
    private SupplierProductRefService supplierProductRefService;

    @Mock
    private SupplierProductRefMapper supplierProductRefMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuFactoryMappingMapper rspuFactoryMappingMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @InjectMocks
    private SupplierIdentityResolutionService resolutionService;

    private SupplierProductRef ref(String rspuId, String factoryCode, Long refId) {
        SupplierProductRef ref = new SupplierProductRef();
        ref.setRefId(refId);
        ref.setRspuId(rspuId);
        ref.setFactoryCode(factoryCode);
        ref.setSourceNamespace(SupplierProductRefService.DEFAULT_NAMESPACE);
        ref.setSourceProductCode("MJ-S96");
        ref.setNormalizedSourceProductCode("MJ-S96");
        return ref;
    }

    private RspuMaster rspu(String rspuId, String externalCode) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setExternalCode(externalCode);
        return rspu;
    }

    private RspuFactoryMapping activeMapping(String factoryCode, String rspuId) {
        RspuFactoryMapping mapping = new RspuFactoryMapping();
        mapping.setFactoryCode(factoryCode);
        mapping.setRspuId(rspuId);
        mapping.setStatus("active");
        return mapping;
    }

    private void factoryExists(String factoryCode) {
        when(factoryMasterMapper.selectById(factoryCode)).thenReturn(new FactoryMaster());
    }

    // ---------- ref 优先 ----------

    @Test
    void resolve_shouldHitSupplierRefFirst() {
        when(supplierProductRefService.findByIdentity("FA", null, "MJ-S96"))
            .thenReturn(Optional.of(ref("RSPU-1", "FA", 11L)));

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("FA", null, "MJ-S96");

        assertThat(result).isPresent();
        assertThat(result.get().getRspuId()).isEqualTo("RSPU-1");
        assertThat(result.get().getSource())
            .isEqualTo(SupplierIdentityResolutionService.ResolutionSource.SUPPLIER_PRODUCT_REF);
        assertThat(result.get().getRefId()).isEqualTo(11L);
        // ref 命中即返回：不再触碰 legacy fallback 相关查询
        verifyNoInteractions(rspuMapper, rspuFactoryMappingMapper, factoryMasterMapper);
    }

    @Test
    void resolve_shouldHitRefForNormalizedEquivalentInput() {
        // §77.1「normalized code 等价输入」：归一逻辑在 SupplierProductRefService 内，
        // 本服务按原文委托，等价输入（全角/大小写/空格变体）同样命中
        when(supplierProductRefService.findByIdentity("FA", null, " mj - s96 "))
            .thenReturn(Optional.of(ref("RSPU-1", "FA", 11L)));

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("FA", null, " mj - s96 ");

        assertThat(result).isPresent();
        assertThat(result.get().getRspuId()).isEqualTo("RSPU-1");
    }

    // ---------- 受限 legacy fallback ----------

    @Test
    void resolve_shouldHitLegacyFallbackWhenUniqueAndFactoryVerified() {
        when(supplierProductRefService.findByIdentity(anyString(), any(), anyString()))
            .thenReturn(Optional.empty());
        factoryExists("FA");
        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu("RSPU-1", "S001")));
        when(rspuFactoryMappingMapper.selectList(any()))
            .thenReturn(List.of(activeMapping("FA", "RSPU-1")));

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("FA", null, "S001");

        assertThat(result).isPresent();
        assertThat(result.get().getRspuId()).isEqualTo("RSPU-1");
        assertThat(result.get().getSource())
            .isEqualTo(SupplierIdentityResolutionService.ResolutionSource.LEGACY_EXTERNAL_CODE);
        assertThat(result.get().getRefId()).isNull();
    }

    @Test
    void resolve_shouldRejectLegacyFallbackWhenAmbiguous() {
        // §77.1「legacy fallback 歧义」：同编码多个候选均可验证属于该工厂 → 未命中，绝不猜测
        when(supplierProductRefService.findByIdentity(anyString(), any(), anyString()))
            .thenReturn(Optional.empty());
        factoryExists("FA");
        when(rspuMapper.selectList(any()))
            .thenReturn(List.of(rspu("RSPU-1", "S001"), rspu("RSPU-2", "S001")));
        when(rspuFactoryMappingMapper.selectList(any()))
            .thenReturn(List.of(activeMapping("FA", "RSPU-1"), activeMapping("FA", "RSPU-2")));

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("FA", null, "S001");

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_shouldRejectLegacyFallbackWhenCandidateNotBelongToFactory() {
        // 候选 RSPU 与该工厂无 active 供应关系 → 无法验证归属，未命中
        when(supplierProductRefService.findByIdentity(anyString(), any(), anyString()))
            .thenReturn(Optional.empty());
        factoryExists("FA");
        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu("RSPU-1", "S001")));
        when(rspuFactoryMappingMapper.selectList(any())).thenReturn(List.of());

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("FA", null, "S001");

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_shouldRejectLegacyFallbackWhenFactoryNotExists() {
        // 工厂上下文不可验证（工厂不存在）→ 不允许回落，且不再查询候选
        when(supplierProductRefService.findByIdentity(anyString(), any(), anyString()))
            .thenReturn(Optional.empty());
        when(factoryMasterMapper.selectById("GHOST")).thenReturn(null);

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("GHOST", null, "S001");

        assertThat(result).isEmpty();
        verifyNoInteractions(rspuMapper, rspuFactoryMappingMapper);
    }

    @Test
    void resolve_shouldPickFactoryVerifiedCandidateAmongMultiple() {
        // 跨工厂同编码（Phase 5 约束下线后的形态）：仅本厂可验证候选唯一时命中
        when(supplierProductRefService.findByIdentity(anyString(), any(), anyString()))
            .thenReturn(Optional.empty());
        factoryExists("FA");
        when(rspuMapper.selectList(any()))
            .thenReturn(List.of(rspu("RSPU-A", "S001"), rspu("RSPU-B", "S001")));
        when(rspuFactoryMappingMapper.selectList(any()))
            .thenReturn(List.of(activeMapping("FA", "RSPU-A")));

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("FA", null, "S001");

        assertThat(result).isPresent();
        assertThat(result.get().getRspuId()).isEqualTo("RSPU-A");
    }

    // ---------- 禁止裸 fallback ----------

    @Test
    void resolve_shouldReturnEmptyWithoutFactoryContext() {
        // 无工厂上下文：禁止裸 fallback（§9 Phase 3），代码上不提供全局 external_code 查询入口
        assertThat(resolutionService.resolve(null, null, "S001")).isEmpty();
        assertThat(resolutionService.resolve("  ", null, "S001")).isEmpty();
        verifyNoInteractions(supplierProductRefService, supplierProductRefMapper,
            rspuMapper, rspuFactoryMappingMapper, factoryMasterMapper);
    }

    @Test
    void resolve_shouldReturnEmptyForBlankCode() {
        assertThat(resolutionService.resolve("FA", null, null)).isEmpty();
        assertThat(resolutionService.resolve("FA", null, "  ")).isEmpty();
        verifyNoInteractions(supplierProductRefService, supplierProductRefMapper,
            rspuMapper, rspuFactoryMappingMapper, factoryMasterMapper);
    }

    // ---------- 批量解析 ----------

    @Test
    void resolveBatch_shouldResolveRefHitLegacyHitAndMiss() {
        // ref 命中：MJ-S96；legacy 命中：S001（唯一 + 工厂验证）；MISS-1：无候选
        SupplierProductRef hit = ref("RSPU-REF", "FA", 21L);
        when(supplierProductRefMapper.selectList(any())).thenReturn(List.of(hit));
        factoryExists("FA");
        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu("RSPU-LEGACY", "S001")));
        when(rspuFactoryMappingMapper.selectList(any()))
            .thenReturn(List.of(activeMapping("FA", "RSPU-LEGACY")));

        Map<String, SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolveBatch("FA", null, List.of("MJ-S96", "S001", "MISS-1"));

        assertThat(result).containsOnlyKeys("MJ-S96", "S001");
        assertThat(result.get("MJ-S96").getRspuId()).isEqualTo("RSPU-REF");
        assertThat(result.get("MJ-S96").getSource())
            .isEqualTo(SupplierIdentityResolutionService.ResolutionSource.SUPPLIER_PRODUCT_REF);
        assertThat(result.get("S001").getRspuId()).isEqualTo("RSPU-LEGACY");
        assertThat(result.get("S001").getSource())
            .isEqualTo(SupplierIdentityResolutionService.ResolutionSource.LEGACY_EXTERNAL_CODE);
    }

    @Test
    void resolveBatch_shouldShareRefHitAcrossNormalizedEquivalentCodes() {
        // 同一归一值的多个原文编码（"MJ-S96" / "mj - s96"）共享同一 ref 命中
        SupplierProductRef hit = ref("RSPU-REF", "FA", 21L);
        when(supplierProductRefMapper.selectList(any())).thenReturn(List.of(hit));

        Map<String, SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolveBatch("FA", null, List.of("MJ-S96", "mj - s96"));

        assertThat(result).containsOnlyKeys("MJ-S96", "mj - s96");
        assertThat(result.get("MJ-S96").getRspuId()).isEqualTo("RSPU-REF");
        assertThat(result.get("mj - s96").getRspuId()).isEqualTo("RSPU-REF");
        // ref 全部命中：不再触碰 legacy fallback
        verifyNoInteractions(rspuMapper, rspuFactoryMappingMapper, factoryMasterMapper);
    }

    @Test
    void resolveBatch_shouldNotFallbackWhenFactoryNotExists() {
        when(supplierProductRefMapper.selectList(any())).thenReturn(List.of());
        when(factoryMasterMapper.selectById("GHOST")).thenReturn(null);

        Map<String, SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolveBatch("GHOST", null, List.of("S001"));

        assertThat(result).isEmpty();
        verifyNoInteractions(rspuMapper, rspuFactoryMappingMapper);
    }

    @Test
    void resolveBatch_shouldReturnEmptyWithoutFactoryContext() {
        Map<String, SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolveBatch(null, null, List.of("S001"));

        assertThat(result).isEmpty();
        verifyNoInteractions(supplierProductRefService, supplierProductRefMapper,
            rspuMapper, rspuFactoryMappingMapper, factoryMasterMapper);
    }

    @Test
    void resolveBatch_shouldSkipBlankAndNullCodes() {
        when(supplierProductRefMapper.selectList(any())).thenReturn(List.of());
        lenient().when(factoryMasterMapper.selectById(anyString())).thenReturn(new FactoryMaster());
        lenient().when(rspuMapper.selectList(any())).thenReturn(List.of());

        Map<String, SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolveBatch("FA", null,
                java.util.Arrays.asList(null, "  ", "S001"));

        assertThat(result).isEmpty();
    }

    @Test
    void resolveBatch_shouldRejectAmbiguousLegacyAndKeepUnambiguous() {
        // S001 两个候选均可验证 → 歧义拒绝；S002 唯一可验证 → 命中
        when(supplierProductRefMapper.selectList(any())).thenReturn(List.of());
        factoryExists("FA");
        when(rspuMapper.selectList(any())).thenReturn(List.of(
            rspu("RSPU-1", "S001"), rspu("RSPU-2", "S001"), rspu("RSPU-3", "S002")));
        when(rspuFactoryMappingMapper.selectList(any())).thenReturn(List.of(
            activeMapping("FA", "RSPU-1"), activeMapping("FA", "RSPU-2"), activeMapping("FA", "RSPU-3")));

        Map<String, SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolveBatch("FA", null, List.of("S001", "S002"));

        assertThat(result).containsOnlyKeys("S002");
        assertThat(result.get("S002").getRspuId()).isEqualTo("RSPU-3");
    }

    @Test
    void resolve_shouldFallbackWithLegacyExactSemanticsWhenRefMisses() {
        // legacy fallback 保持历史精确语义（trim 等值）：ref 未命中时，
        // 仅 exact trim 匹配 external_code 才进入工厂验证（归一化只在 ref 路径生效）
        when(supplierProductRefService.findByIdentity(anyString(), isNull(), anyString()))
            .thenReturn(Optional.empty());
        factoryExists("FA");
        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu("RSPU-1", "MJ -S96")));
        when(rspuFactoryMappingMapper.selectList(any()))
            .thenReturn(List.of(activeMapping("FA", "RSPU-1")));

        Optional<SupplierIdentityResolutionService.ResolvedIdentity> result =
            resolutionService.resolve("FA", null, "MJ -S96");

        assertThat(result).isPresent();
        assertThat(result.get().getSource())
            .isEqualTo(SupplierIdentityResolutionService.ResolutionSource.LEGACY_EXTERNAL_CODE);
    }
}
