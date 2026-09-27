package com.rsdp.supplychain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportPolicy} / {@link ImportPolicyMaterializer} 单元测试
 * （设计文档 v3.3 §47.1.5 / §47.1.6：默认值物化、未知枚举拒绝、overrideReason 联动、
 * 正文全字段契约与不可变 Hash 校验）。
 */
class ImportPolicyMaterializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------ ImportPolicy.materialize

    @Test
    void defaults_fillAllFields() {
        ImportPolicy p = ImportPolicy.defaults();
        assertFalse(p.updateExisting());
        assertFalse(p.allowWarnings());
        assertEquals("fail_group", p.conflictPolicy());
        assertEquals("replace_if_newer", p.quotePolicy());
        assertEquals("preserve_existing", p.nullValuePolicy());
        assertEquals("preserve", p.manualCorrectionPolicy());
        assertNull(p.overrideReason());
    }

    @Test
    void sparseInput_fallsBackToDefaults() {
        ImportPolicy p = ImportPolicy.materialize(true, null, "  ", null, null, null, null);
        assertTrue(p.updateExisting());
        assertEquals("fail_group", p.conflictPolicy());
        assertEquals("replace_if_newer", p.quotePolicy());
    }

    @Test
    void unimplementedBranches_rejected() {
        // 2026-09-24 评审：只有协议没有行为的非默认选项直接拒绝，不产生与声明不符的 Attempt
        assertThrows(IllegalArgumentException.class, () -> ImportPolicy.materialize(
            null, true, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ImportPolicy.materialize(
            null, null, "skip_invalid_target", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ImportPolicy.materialize(
            null, null, null, null, "clear_explicit_only", null, null));
        assertThrows(IllegalArgumentException.class, () -> ImportPolicy.materialize(
            null, null, null, null, null, "replace_explicit", null));
        // 默认值不受影响
        ImportPolicy p = ImportPolicy.defaults();
        assertFalse(p.allowWarnings());
    }

    @Test
    void unknownEnum_rejected() {
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, "merge_anyway", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, null, "latest_wins", null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, null, null, "overwrite", null, null));
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, null, null, null, "ignore_manual", null));
    }

    @Test
    void forceReplace_requiresOverrideReason() {
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, null, "force_replace", null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, null, "force_replace", null, null, "   "));
        ImportPolicy p = ImportPolicy.materialize(null, null, null, "force_replace", null, null,
            "客户确认涨价覆盖");
        assertEquals("客户确认涨价覆盖", p.overrideReason());
        assertTrue(p.isForceReplace());
    }

    @Test
    void overrideReason_onlyAllowedWithForceReplace() {
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, null, "replace_if_newer", null, null, "不该带"));
        assertThrows(IllegalArgumentException.class, () ->
            ImportPolicy.materialize(null, null, null, "keep_existing", null, null, "不该带"));
    }

    // ------------------------------------------------------------ materialize / parse / verify

    @Test
    void materializedPayload_hasFullSchemaV1Fields_withExplicitNull() throws Exception {
        ImportPolicyMaterializer.MaterializedPolicy m =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        JsonNode node = MAPPER.readTree(m.policyPayload());
        assertEquals(7, node.size());
        for (String field : new String[]{"updateExisting", "allowWarnings", "conflictPolicy",
            "quotePolicy", "nullValuePolicy", "manualCorrectionPolicy", "overrideReason"}) {
            assertTrue(node.has(field), "缺少字段: " + field);
        }
        assertTrue(node.get("overrideReason").isNull());
        assertEquals(ImportPolicyMaterializer.POLICY_HASH_VERSION, m.policyHashVersion());
        assertEquals(64, m.policyHash().length());
    }

    @Test
    void materialize_isDeterministic() {
        ImportPolicy policy = ImportPolicy.materialize(true, null, null,
            "keep_existing", null, null, null);
        ImportPolicyMaterializer.MaterializedPolicy first = ImportPolicyMaterializer.materialize(policy);
        for (int i = 0; i < 20; i++) {
            assertEquals(first.policyHash(), ImportPolicyMaterializer.materialize(policy).policyHash());
            assertEquals(first.policyPayload(), ImportPolicyMaterializer.materialize(policy).policyPayload());
        }
    }

    @Test
    void differentPolicy_differentHash() {
        ImportPolicyMaterializer.MaterializedPolicy a =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        ImportPolicyMaterializer.MaterializedPolicy b = ImportPolicyMaterializer.materialize(
            ImportPolicy.materialize(true, null, null, null, null, null, null));
        assertNotEquals(a.policyHash(), b.policyHash());
    }

    @Test
    void parse_roundTrips() {
        ImportPolicy policy = ImportPolicy.materialize(true, null, null,
            "force_replace", null, null, "人工确认覆盖");
        ImportPolicyMaterializer.MaterializedPolicy m = ImportPolicyMaterializer.materialize(policy);
        assertEquals(policy, ImportPolicyMaterializer.parse(m.policyPayload()));
    }

    @Test
    void parse_rejectsMissingKey() {
        // 显式 null 契约：缺键不等于 null（§47.1.1），缺 overrideReason 键必须拒绝
        String broken = "{\"updateExisting\":false,\"allowWarnings\":false,"
            + "\"conflictPolicy\":\"fail_group\",\"quotePolicy\":\"replace_if_newer\","
            + "\"nullValuePolicy\":\"preserve_existing\",\"manualCorrectionPolicy\":\"preserve\"}";
        assertThrows(IllegalArgumentException.class, () -> ImportPolicyMaterializer.parse(broken));
    }

    @Test
    void parse_rejectsBlankAndNonObject() {
        assertThrows(IllegalArgumentException.class, () -> ImportPolicyMaterializer.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ImportPolicyMaterializer.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> ImportPolicyMaterializer.parse("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> ImportPolicyMaterializer.parse("not-json"));
    }

    @Test
    void verify_acceptsStoredMaterial() {
        ImportPolicyMaterializer.MaterializedPolicy m =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        assertTrue(ImportPolicyMaterializer.verify(m.policyPayload(), m.policyHashVersion(), m.policyHash()));
    }

    @Test
    void verify_toleratesKeyOrder_difference() {
        ImportPolicyMaterializer.MaterializedPolicy m =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        // canonical 等价但键序不同的正文应通过（键序无关，§47.1.1）
        String reordered = "{\"overrideReason\":null,\"manualCorrectionPolicy\":\"preserve\","
            + "\"nullValuePolicy\":\"preserve_existing\",\"quotePolicy\":\"replace_if_newer\","
            + "\"conflictPolicy\":\"fail_group\",\"allowWarnings\":false,\"updateExisting\":false}";
        assertTrue(ImportPolicyMaterializer.verify(reordered, m.policyHashVersion(), m.policyHash()));
    }

    @Test
    void verify_rejectsTamperedPayload_hashAndExtraField() {
        ImportPolicyMaterializer.MaterializedPolicy m =
            ImportPolicyMaterializer.materialize(ImportPolicy.defaults());
        // 篡改字段值：重算 Hash 不一致
        String tampered = m.policyPayload().replace("\"updateExisting\":false", "\"updateExisting\":true");
        assertFalse(ImportPolicyMaterializer.verify(tampered, m.policyHashVersion(), m.policyHash()));
        // 多余字段：canonical 字节不等价，视为篡改
        String extra = m.policyPayload().replace("}", ",\"workerId\":\"w-1\"}");
        assertFalse(ImportPolicyMaterializer.verify(extra, m.policyHashVersion(), m.policyHash()));
        // Hash 版本不匹配
        assertFalse(ImportPolicyMaterializer.verify(m.policyPayload(), 999, m.policyHash()));
        // Hash 被替换
        assertFalse(ImportPolicyMaterializer.verify(m.policyPayload(), m.policyHashVersion(),
            "0".repeat(64)));
    }
}
