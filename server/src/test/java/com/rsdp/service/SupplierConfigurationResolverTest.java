package com.rsdp.service;

import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link SupplierConfigurationResolver} 单元测试（v3.3 §18.3，P0-D）。
 */
@ExtendWith(MockitoExtension.class)
class SupplierConfigurationResolverTest {

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @InjectMocks
    private SupplierConfigurationResolver resolver;

    private static RskuSupply rsku(String rskuId, String configKey, String gradeCode, String gradeText) {
        RskuSupply r = new RskuSupply();
        r.setRskuId(rskuId);
        r.setVariantId("VAR-1");
        r.setFactoryCode("F001");
        r.setSupplierConfigurationKey(configKey);
        r.setSupplierMaterialGradeCode(gradeCode);
        r.setSupplierMaterialGradeText(gradeText);
        return r;
    }

    @Test
    void exactCodeKeyHitShouldBindOriginalRsku() {
        RskuSupply existing = rsku("RSKU-1", "CODE:FABRIC_AA", "FABRIC_AA", null);
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(existing);

        var result = resolver.resolve("VAR-1", "F001", "fabric_aa", null);

        assertThat(result.kind())
            .isEqualTo(SupplierConfigurationResolver.ResolutionKind.EXACT_HIT);
        assertThat(result.rskuId()).isEqualTo("RSKU-1");
        assertThat(result.supplierConfigurationKey()).isEqualTo("CODE:FABRIC_AA");
    }

    @Test
    void codeInputShouldBindLegacyTextKeyRsku() {
        // §18.3 / §77.4：TEXT:AA级布 后续获得 CODE:FABRIC_AA 时命中原 rsku_id，不创建第二条
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(null);
        RskuSupply legacy = rsku("RSKU-OLD", "TEXT:AA级布", null, "AA级布");
        legacy.setSupplierMaterialGradeCode("FABRIC_AA"); // 治理后保留的 code↔text 对应
        when(rskuSupplyMapper.selectAliveByVariantFactory("VAR-1", "F001"))
            .thenReturn(List.of(legacy));

        var result = resolver.resolve("VAR-1", "F001", "FABRIC_AA", "AA级布");

        assertThat(result.kind())
            .isEqualTo(SupplierConfigurationResolver.ResolutionKind.BOUND_LEGACY_TEXT_KEY);
        assertThat(result.rskuId()).isEqualTo("RSKU-OLD");
        // 绑定不自动升级 Key：受控迁移由 SupplierConfigurationMigrationService 执行
        assertThat(result.supplierConfigurationKey()).isEqualTo("TEXT:AA级布");
    }

    @Test
    void codeInputWithUnexcludableLegacyCandidateShouldConflict() {
        // §18.3：输入只有 code 且存在无等级信息、无法排除的旧候选 → 冲突，不创建新 Key
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(null);
        RskuSupply baseRow = rsku("RSKU-BASE", "BASE", null, null);
        when(rskuSupplyMapper.selectAliveByVariantFactory("VAR-1", "F001"))
            .thenReturn(List.of(baseRow));

        var result = resolver.resolve("VAR-1", "F001", "FABRIC_AA", null);

        assertThat(result.isConflict()).isTrue();
        assertThat(result.conflictReason()).contains("无法排除");
    }

    @Test
    void codeInputWithCodeAndTextAllowsExclusionAndCreates() {
        // 输入同时携带 code+text，旧 BASE 行可被 text 排除 → 允许创建新 CODE Key
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(null);
        RskuSupply baseRow = rsku("RSKU-BASE", "BASE", null, null);
        when(rskuSupplyMapper.selectAliveByVariantFactory("VAR-1", "F001"))
            .thenReturn(List.of(baseRow));

        var result = resolver.resolve("VAR-1", "F001", "FABRIC_AA", "AA级布");

        assertThat(result.isCreateNew()).isTrue();
        assertThat(result.supplierConfigurationKey()).isEqualTo("CODE:FABRIC_AA");
    }

    @Test
    void codeInputMatchingMultipleLegacyTextKeysShouldConflict() {
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(null);
        when(rskuSupplyMapper.selectAliveByVariantFactory("VAR-1", "F001"))
            .thenReturn(List.of(
                rsku("RSKU-A", "TEXT:AA级布", "FABRIC_AA", "AA级布"),
                rsku("RSKU-B", "TEXT:AA级布-旧", "FABRIC_AA", null)));

        var result = resolver.resolve("VAR-1", "F001", "FABRIC_AA", null);

        assertThat(result.isConflict()).isTrue();
        assertThat(result.conflictReason()).contains("多条");
    }

    @Test
    void textInputShouldBindUpgradedCodeKeyRsku() {
        // TEXT 输入反向命中已升级 CODE 的原 RSKU（其保留 grade text 与输入原文对应）
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "TEXT:AA级布"))
            .thenReturn(null);
        RskuSupply upgraded = rsku("RSKU-NEW", "CODE:FABRIC_AA", "FABRIC_AA", "AA级布");
        when(rskuSupplyMapper.selectAliveByVariantFactory("VAR-1", "F001"))
            .thenReturn(List.of(upgraded));

        var result = resolver.resolve("VAR-1", "F001", null, "AA级布");

        assertThat(result.kind())
            .isEqualTo(SupplierConfigurationResolver.ResolutionKind.BOUND_UPGRADED_CODE_KEY);
        assertThat(result.rskuId()).isEqualTo("RSKU-NEW");
        assertThat(result.supplierConfigurationKey()).isEqualTo("CODE:FABRIC_AA");
    }

    @Test
    void baseKeyShouldCreateWhenMissing() {
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "BASE")).thenReturn(null);

        var result = resolver.resolve("VAR-1", "F001", null, null);

        assertThat(result.isCreateNew()).isTrue();
        assertThat(result.supplierConfigurationKey()).isEqualTo("BASE");
    }

    @Test
    void textKeyCreateNewWhenNoCandidates() {
        // §56：同一 Variant 下 A/AA/S 级布生成三个非 BASE Key，互不覆盖
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "TEXT:S级布"))
            .thenReturn(null);
        when(rskuSupplyMapper.selectAliveByVariantFactory("VAR-1", "F001"))
            .thenReturn(List.of(
                rsku("RSKU-A", "TEXT:A级布", null, "A级布"),
                rsku("RSKU-AA", "TEXT:AA级布", null, "AA级布")));

        var result = resolver.resolve("VAR-1", "F001", null, "S级布");

        assertThat(result.isCreateNew()).isTrue();
        assertThat(result.supplierConfigurationKey()).isEqualTo("TEXT:S级布");
    }
}
