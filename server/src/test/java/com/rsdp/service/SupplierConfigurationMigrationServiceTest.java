package com.rsdp.service;

import com.rsdp.dto.SupplierConfigurationDryRunReport;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SupplierConfigurationMigrationService} 单元测试（v3.3 §18.3，P0-D）。
 */
@ExtendWith(MockitoExtension.class)
class SupplierConfigurationMigrationServiceTest {

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private SupplierConfigurationMigrationService service;

    private static RskuSupply rsku(String rskuId, String variantId, String factoryCode,
                                   String configKey, String gradeCode, String gradeText,
                                   String version) {
        RskuSupply r = new RskuSupply();
        r.setRskuId(rskuId);
        r.setRspuId("RSPU-1");
        r.setVariantId(variantId);
        r.setFactoryCode(factoryCode);
        r.setSupplierConfigurationKey(configKey);
        r.setSupplierMaterialGradeCode(gradeCode);
        r.setSupplierMaterialGradeText(gradeText);
        r.setSupplierConfigurationNormalizerVersion(version);
        return r;
    }

    // ---------------- TEXT→CODE 受控 Key Migration ----------------

    @Test
    void migrateTextKeyToCode_shouldKeepRskuIdAndRewriteKey() {
        RskuSupply locked = rsku("RSKU-1", "VAR-1", "F001", "TEXT:AA级布", null, "AA级布", "1");
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-1")).thenReturn(locked);
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(null);

        var result = service.migrateTextKeyToCode("RSKU-1", "fabric_aa", "admin");

        assertThat(result.rskuId()).isEqualTo("RSKU-1");
        assertThat(result.oldKey()).isEqualTo("TEXT:AA级布");
        assertThat(result.newKey()).isEqualTo("CODE:FABRIC_AA");
        assertThat(result.normalizerVersion()).isEqualTo("1");
        assertThat(locked.getSupplierConfigurationKey()).isEqualTo("CODE:FABRIC_AA");
        assertThat(locked.getSupplierMaterialGradeCode()).isEqualTo("fabric_aa");
        verify(rskuSupplyMapper).updateById(locked);
        verify(auditLogService).logUpdate(eq("rsku_supply"), eq("RSKU-1"), any(), any(), eq("admin"));
    }

    @Test
    void migrateTextKeyToCode_shouldRejectWhenTargetOccupied() {
        // §18.3：目标 CODE Key 已属于另一 RSKU → 冲突治理，不自动合并
        RskuSupply locked = rsku("RSKU-1", "VAR-1", "F001", "TEXT:AA级布", null, "AA级布", "1");
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-1")).thenReturn(locked);
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(rsku("RSKU-2", "VAR-1", "F001", "CODE:FABRIC_AA", "FABRIC_AA", null, "1"));

        assertThatThrownBy(() -> service.migrateTextKeyToCode("RSKU-1", "FABRIC_AA", "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已被同一 Variant+Factory 下的另一 RSKU 占用");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void migrateTextKeyToCode_shouldRejectNonTextKey() {
        RskuSupply locked = rsku("RSKU-1", "VAR-1", "F001", "BASE", null, null, "1");
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-1")).thenReturn(locked);

        assertThatThrownBy(() -> service.migrateTextKeyToCode("RSKU-1", "FABRIC_AA", "admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅 TEXT Key 支持受控升级");
    }

    @Test
    void migrateTextKeyToCode_shouldRejectMissingRsku() {
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-X")).thenReturn(null);
        assertThatThrownBy(() -> service.migrateTextKeyToCode("RSKU-X", "FABRIC_AA", "admin"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void migrateTextKeyToCode_shouldBeIdempotentWhenAlreadyOnTargetKey() {
        // 目标 CODE Key 已被自己占用（重放/重试）→ 幂等成功，不报错
        RskuSupply locked = rsku("RSKU-1", "VAR-1", "F001", "TEXT:AA级布", null, "AA级布", "1");
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-1")).thenReturn(locked);
        when(rskuSupplyMapper.selectAliveByConfig("VAR-1", "F001", "CODE:FABRIC_AA"))
            .thenReturn(locked);

        var result = service.migrateTextKeyToCode("RSKU-1", "FABRIC_AA", "admin");
        assertThat(result.newKey()).isEqualTo("CODE:FABRIC_AA");
    }

    // ---------------- Normalizer 升级 Dry Run ----------------

    @Test
    void dryRun_shouldClassifyStableAndOneToOne() {
        // stable：新旧结果不变（BASE 行恒 stable）；one-to-one：唯一安全更名
        // 当前只有 v1 版本，构造目标版本=v1 时全部 stable 过于平凡；
        // 这里用「等级原文归一后等于现 Key 载荷」的行验证 stable，
        // 用 grade 原文含全角/大小写差异但现 Key 已是归一值的行验证 stable 不变。
        List<RskuSupply> rows = List.of(
            rsku("RSKU-1", "VAR-1", "F001", "BASE", null, null, "1"),
            rsku("RSKU-2", "VAR-1", "F001", "CODE:FABRIC_AA", "fabric_aa", null, "1"),
            rsku("RSKU-3", "VAR-2", "F001", "TEXT:AA级布", null, "AA级布", "1"));
        when(rskuSupplyMapper.selectAliveForConfigurationScan()).thenReturn(rows);

        SupplierConfigurationDryRunReport report = service.dryRunNormalizerUpgrade(1);

        assertThat(report.totalScanned()).isEqualTo(3);
        assertThat(report.stableCount()).isEqualTo(3);
        assertThat(report.oneToOne()).isEmpty();
        assertThat(report.converged()).isEmpty();
        assertThat(report.ambiguous()).isEmpty();
        assertThat(report.isSwitchAllowed()).isTrue();
        assertThat(report.unknownVersionCount()).isZero();
    }

    @Test
    void dryRun_shouldDetectConvergedWhenMultipleOldKeysMapToSameNewKey() {
        // converged：多个旧 Key 收敛到同一新 Key（两行 grade code 原文不同但归一后相同，
        // 重算后都从历史 TEXT Key 收敛到同一 CODE Key）
        List<RskuSupply> rows = List.of(
            rsku("RSKU-1", "VAR-1", "F001", "TEXT:老一级", "AA", null, "1"),
            rsku("RSKU-2", "VAR-1", "F001", "TEXT:老二级", "aa ", null, "1"));
        when(rskuSupplyMapper.selectAliveForConfigurationScan()).thenReturn(rows);

        SupplierConfigurationDryRunReport report = service.dryRunNormalizerUpgrade(1);

        assertThat(report.converged()).hasSize(1);
        var convergence = report.converged().get(0);
        assertThat(convergence.newKey()).isEqualTo("CODE:AA");
        assertThat(convergence.oldKeys())
            .containsExactlyInAnyOrder("TEXT:老一级", "TEXT:老二级");
        assertThat(convergence.rskuIds())
            .containsExactlyInAnyOrder("RSKU-1", "RSKU-2");
        assertThat(report.isSwitchAllowed()).isFalse();
    }

    @Test
    void dryRun_shouldDetectAmbiguousWhenNewKeyOccupiedByOtherConfig() {
        // ambiguous：新 Key 被另一当前有效配置占用
        // RSKU-1 的 grade 原文归一后撞 RSKU-2 当前已占用的 CODE Key
        List<RskuSupply> rows = List.of(
            rsku("RSKU-1", "VAR-1", "F001", "TEXT:fabric_aa", "fabric_aa", null, "1"),
            rsku("RSKU-2", "VAR-1", "F001", "CODE:FABRIC_AA", "FABRIC_AA", null, "1"));
        when(rskuSupplyMapper.selectAliveForConfigurationScan()).thenReturn(rows);

        SupplierConfigurationDryRunReport report = service.dryRunNormalizerUpgrade(1);

        // RSKU-1：TEXT:fabric_aa → CODE:FABRIC_AA（有 grade code 时新 Key 走 CODE），被 RSKU-2 占用
        assertThat(report.ambiguous()).hasSize(1);
        var ambiguity = report.ambiguous().get(0);
        assertThat(ambiguity.rskuId()).isEqualTo("RSKU-1");
        assertThat(ambiguity.newKey()).isEqualTo("CODE:FABRIC_AA");
        assertThat(ambiguity.occupantRskuId()).isEqualTo("RSKU-2");
        assertThat(report.isSwitchAllowed()).isFalse();
    }

    @Test
    void dryRun_shouldReportOneToOneRename() {
        // one-to-one：新 Key 与旧 Key 不同、且未被占用、且不收敛
        List<RskuSupply> rows = List.of(
            // 旧 Key 为 TEXT（code 缺失时的历史遗留），grade code 治理补齐后重算得 CODE Key
            rsku("RSKU-1", "VAR-1", "F001", "TEXT:AA级布", "FABRIC_AA", "AA级布", "1"));
        when(rskuSupplyMapper.selectAliveForConfigurationScan()).thenReturn(rows);

        SupplierConfigurationDryRunReport report = service.dryRunNormalizerUpgrade(1);

        assertThat(report.oneToOne()).hasSize(1);
        assertThat(report.oneToOne().get(0).oldKey()).isEqualTo("TEXT:AA级布");
        assertThat(report.oneToOne().get(0).newKey()).isEqualTo("CODE:FABRIC_AA");
        assertThat(report.isSwitchAllowed()).isTrue();
    }

    @Test
    void dryRun_shouldRejectUnsupportedVersion() {
        assertThatThrownBy(() -> service.dryRunNormalizerUpgrade(99))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
