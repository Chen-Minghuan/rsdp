package com.rsdp.service;

import com.rsdp.dto.NormalizerCodeChange;
import com.rsdp.dto.NormalizerCodeConflict;
import com.rsdp.dto.NormalizerUpgradeDryRunReport;
import com.rsdp.entity.SupplierProductRef;
import com.rsdp.mapper.SupplierProductRefMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link SourceCodeNormalizerDryRunService} 单元测试（设计文档 v3.3 §7 五步流程 / §77.1）。
 */
@ExtendWith(MockitoExtension.class)
class SourceCodeNormalizerDryRunServiceTest {

    private static final int TARGET_VERSION = 1;

    @Mock
    private SupplierProductRefMapper mapper;

    private SourceCodeNormalizerDryRunService service;

    private SourceCodeNormalizerDryRunService service() {
        if (service == null) {
            service = new SourceCodeNormalizerDryRunService(mapper);
        }
        return service;
    }

    private static SupplierProductRef row(String factoryCode, String namespace, String rawCode,
                                          String normalizedCode, String version) {
        SupplierProductRef ref = new SupplierProductRef();
        ref.setFactoryCode(factoryCode);
        ref.setSourceNamespace(namespace);
        ref.setSourceProductCode(rawCode);
        ref.setNormalizedSourceProductCode(normalizedCode);
        ref.setCodeNormalizerVersion(version);
        return ref;
    }

    @Test
    void dryRun_shouldRejectUnsupportedTargetVersion() {
        assertThrows(IllegalArgumentException.class, () -> service().dryRun(99));
    }

    @Test
    void dryRun_shouldReportAllUnchangedWhenDataMatchesTargetVersion() {
        when(mapper.selectDistinctIdentityCodes()).thenReturn(List.of(
            row("FA", "default", "MJ-S96", "MJ-S96", "1"),
            row("FB", "default", "MJ-S96", "MJ-S96", "1")));

        NormalizerUpgradeDryRunReport report = service().dryRun(TARGET_VERSION);

        assertEquals(TARGET_VERSION, report.getTargetNormalizerVersion());
        assertEquals(2, report.getScannedCount());
        assertEquals(2, report.getUnchangedCount());
        assertTrue(report.getChanges().isEmpty());
        assertTrue(report.getConflicts().isEmpty());
        assertEquals(0, report.getUnknownVersionCount());
        assertTrue(report.isSwitchAllowed());
    }

    @Test
    void dryRun_shouldDetectOldToNewValueChanges() {
        // 存量行为旧归一值（旧版本残留），按目标版本重算后发生变化
        when(mapper.selectDistinctIdentityCodes()).thenReturn(List.of(
            row("FA", "default", "MJ - S96", "MJ - S96", "1")));

        NormalizerUpgradeDryRunReport report = service().dryRun(TARGET_VERSION);

        assertEquals(1, report.getChanges().size());
        NormalizerCodeChange change = report.getChanges().get(0);
        assertEquals("FA", change.getFactoryCode());
        assertEquals("default", change.getSourceNamespace());
        assertEquals("MJ - S96", change.getSourceProductCode());
        assertEquals("MJ - S96", change.getOldNormalizedCode());
        assertEquals("MJ-S96", change.getNewNormalizedCode());
        assertEquals("1", change.getOldNormalizerVersion());
        assertEquals(TARGET_VERSION, change.getTargetNormalizerVersion());
        assertEquals(0, report.getUnchangedCount());
        assertTrue(report.getConflicts().isEmpty());
        assertTrue(report.isSwitchAllowed());
    }

    @Test
    void dryRun_shouldDetectCollisionWhenNewNormalizedValuesConverge() {
        // §77.1「Normalizer 升级 Dry Run 能发现新归一值冲突」：
        // 两个不同原文按目标版本归一到同一值 → 迁移必撞唯一键
        when(mapper.selectDistinctIdentityCodes()).thenReturn(List.of(
            row("FA", "default", "MJ-S96", "MJ-S96", "1"),
            row("FA", "default", "MJ - S96", "MJ - S96", "1")));

        NormalizerUpgradeDryRunReport report = service().dryRun(TARGET_VERSION);

        assertEquals(1, report.getConflicts().size());
        NormalizerCodeConflict conflict = report.getConflicts().get(0);
        assertEquals("FA", conflict.getFactoryCode());
        assertEquals("default", conflict.getSourceNamespace());
        assertEquals("MJ-S96", conflict.getNewNormalizedCode());
        assertEquals(List.of("MJ - S96", "MJ-S96"), conflict.getConflictingSourceCodes());
        // 冲突未治理前不得切换
        assertFalse(report.isSwitchAllowed());
    }

    @Test
    void dryRun_shouldNotCollideAcrossFactoriesOrNamespaces() {
        // §77.1「Factory A / S001；Factory B / S001」：跨工厂 / 跨 namespace 允许同码
        when(mapper.selectDistinctIdentityCodes()).thenReturn(List.of(
            row("FA", "default", "MJ-S96", "MJ-S96", "1"),
            row("FB", "default", "MJ - S96", "MJ - S96", "1"),
            row("FA", "erp", "MJ - S96", "MJ - S96", "1")));

        NormalizerUpgradeDryRunReport report = service().dryRun(TARGET_VERSION);

        assertTrue(report.getConflicts().isEmpty());
        assertTrue(report.isSwitchAllowed());
    }

    @Test
    void dryRun_shouldFlagUnknownStoredVersionAndBlockSwitch() {
        when(mapper.selectDistinctIdentityCodes()).thenReturn(List.of(
            row("FA", "default", "MJ-S96", "MJ-S96", "99"),
            row("FA", "default", "FS-01", "FS-01", null)));

        NormalizerUpgradeDryRunReport report = service().dryRun(TARGET_VERSION);

        assertEquals(2, report.getUnknownVersionCount());
        // 版本信息不可信的行也列入变化清单（需重写版本号）
        assertEquals(2, report.getChanges().size());
        assertFalse(report.isSwitchAllowed());
    }

    @Test
    void dryRun_shouldAllowSwitchWhenNoConflict() {
        when(mapper.selectDistinctIdentityCodes()).thenReturn(List.of(
            row("FA", "default", "MJ-S96", "MJ-S96", "1")));

        NormalizerUpgradeDryRunReport report = service().dryRun(TARGET_VERSION);

        assertTrue(report.isSwitchAllowed());
    }
}
