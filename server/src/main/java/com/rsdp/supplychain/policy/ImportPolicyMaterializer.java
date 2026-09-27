package com.rsdp.supplychain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.supplychain.hash.SnapshotHasher;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Import Policy 正文 + Hash 物化器（设计文档 v3.3 §47.1.5 / §47.1.6，P0-F）。
 *
 * <p>policy_hash 材料（§47.1.5）：{@code policy_hash_version + policy_schema_version +
 * canonical(policy_payload)}；canonical 遵循 §47.1.1（UTF-8、字典序 key、显式 null 保留），
 * 由 {@link SnapshotHasher} / CanonicalSerializer 统一实现。</p>
 *
 * <p>物化产物（{@link MaterializedPolicy}）包含完整正文 JSON 与 Hash，二者必须在创建
 * Attempt 的同一事务写入 {@code excel_import_attempt}；执行与恢复只读 Attempt 上存储的
 * 正文并用 Hash 校验请求重放的一致性（不可变校验）。</p>
 */
public final class ImportPolicyMaterializer {

    /** Policy Hash 协议版本（§47.1.6，excel_import_attempt.policy_hash_version）。 */
    public static final int POLICY_HASH_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImportPolicyMaterializer() {
    }

    /**
     * 物化结果：不可变 Policy + 完整正文 JSON + 版本化 Hash。
     *
     * @param policy            完整物化的 Policy
     * @param policyPayload     正文 JSON（schema v1 全字段，显式 null）
     * @param policyHashVersion Hash 协议版本
     * @param policyHash        SHA-256 摘要
     */
    public record MaterializedPolicy(ImportPolicy policy, String policyPayload,
                                     int policyHashVersion, String policyHash) {
    }

    /**
     * 把 Policy 物化为正文 + Hash。
     *
     * @param policy 已补齐默认值的 Policy（{@link ImportPolicy#materialize} 产物）
     * @return 物化结果
     */
    public static MaterializedPolicy materialize(ImportPolicy policy) {
        Objects.requireNonNull(policy, "policy 不能为 null");
        ObjectNode payload = MAPPER.createObjectNode();
        // schema v1 全字段契约（§47.1.5）：字段集合固定，显式 null 保留（§47.1.1 规则 5）
        payload.put("updateExisting", policy.updateExisting());
        payload.put("allowWarnings", policy.allowWarnings());
        payload.put("conflictPolicy", policy.conflictPolicy());
        payload.put("quotePolicy", policy.quotePolicy());
        payload.put("nullValuePolicy", policy.nullValuePolicy());
        payload.put("manualCorrectionPolicy", policy.manualCorrectionPolicy());
        if (policy.overrideReason() == null) {
            payload.putNull("overrideReason");
        } else {
            payload.put("overrideReason", policy.overrideReason());
        }
        ObjectNode material = MAPPER.createObjectNode();
        material.put("policyHashVersion", POLICY_HASH_VERSION);
        material.put("policySchemaVersion", ImportPolicy.SCHEMA_VERSION);
        material.set("policyPayload", payload);
        return new MaterializedPolicy(policy, payload.toString(),
            POLICY_HASH_VERSION, SnapshotHasher.hash(POLICY_HASH_VERSION, material));
    }

    /**
     * 从存储的 Policy 正文重建 Policy 对象（Worker / Recovery 只读 Attempt 上的 Snapshot，§49）。
     *
     * @param policyPayload 存储的正文 JSON
     * @return Policy；正文缺字段或枚举非法时抛 {@link IllegalArgumentException}
     */
    public static ImportPolicy parse(String policyPayload) {
        if (policyPayload == null || policyPayload.isBlank()) {
            throw new IllegalArgumentException("policy_payload 不能为空");
        }
        try {
            JsonNode node = MAPPER.readTree(policyPayload);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("policy_payload 必须是 JSON object");
            }
            // 显式 null 保留契约：缺失键一律拒绝（不允许「缺省」与「null」两种合法字节，§47.1.1）
            for (String field : new String[]{"updateExisting", "allowWarnings", "conflictPolicy",
                "quotePolicy", "nullValuePolicy", "manualCorrectionPolicy", "overrideReason"}) {
                if (!node.has(field)) {
                    throw new IllegalArgumentException("policy_payload 缺少字段: " + field);
                }
            }
            return ImportPolicy.materialize(
                node.get("updateExisting").asBoolean(),
                node.get("allowWarnings").asBoolean(),
                node.get("conflictPolicy").asText(),
                node.get("quotePolicy").asText(),
                node.get("nullValuePolicy").asText(),
                node.get("manualCorrectionPolicy").asText(),
                node.get("overrideReason").isNull() ? null : node.get("overrideReason").asText());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("policy_payload 解析失败", e);
        }
    }

    /**
     * 重放校验：对存储正文重算 Hash 并与存储 Hash 常量时间比较（不可变校验）。
     *
     * @param storedPayload   存储的 policy_payload
     * @param storedHash      存储的 policy_hash
     * @param storedHashVersion 存储的 policy_hash_version
     * @return 一致返回 true
     */
    public static boolean verify(String storedPayload, int storedHashVersion, String storedHash) {
        if (storedHashVersion != POLICY_HASH_VERSION) {
            return false;
        }
        ImportPolicy policy = parse(storedPayload);
        MaterializedPolicy recomputed = materialize(policy);
        // 正文本身也须字节等价（ canonical 层之外的差异，如多余字段，视为篡改）
        if (!canonicalEquals(storedPayload, recomputed.policyPayload())) {
            return false;
        }
        return SnapshotHasher.matches(storedHash, recomputed.policyHash());
    }

    /** 规范化后的字节等价比较（键序无关、显式 null 语义一致）。 */
    private static boolean canonicalEquals(String left, String right) {
        try {
            Map<String, JsonNode> l = toSortedMap(MAPPER.readTree(left));
            Map<String, JsonNode> r = toSortedMap(MAPPER.readTree(right));
            return l.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, JsonNode> toSortedMap(JsonNode node) {
        Map<String, JsonNode> map = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }
}
