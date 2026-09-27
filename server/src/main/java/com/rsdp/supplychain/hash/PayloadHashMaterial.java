package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Product Group 标准化负载哈希材料（设计文档 §47.1.2 PayloadHashMaterial）。
 *
 * <p>Material 固定为：</p>
 *
 * <pre>
 * payload_hash_version
 * + canonical(public normalized payload)
 * + 按 value_path 排序的：sensitiveRef / value_hmac / hmac_key_version
 * </pre>
 *
 * <p>因此敏感值变化会改变 payload_hash，但同一业务值重新加密得到不同 Ciphertext
 * 时不会造成 Hash 漂移（Material 只含 HMAC，不含密文，§47.1.1 第 9 条）。</p>
 *
 * <p>B1 阶段敏感值表（§24.2 excel_import_sensitive_value）尚未落地，
 * {@code sensitiveValues} 允许为空列表；B2 写入敏感值时调用方必须按
 * {@code valuePath} 提供条目，本类构造时统一排序，调用方传入顺序不影响结果。</p>
 */
public final class PayloadHashMaterial {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int payloadHashVersion;
    private final JsonNode payload;
    private final List<SensitiveValueEntry> sensitiveValues;

    private PayloadHashMaterial(int payloadHashVersion, JsonNode payload,
                                List<SensitiveValueEntry> sensitiveValues) {
        if (payloadHashVersion <= 0) {
            throw new IllegalArgumentException("payloadHashVersion 必须 > 0（§47.1.6）");
        }
        this.payloadHashVersion = payloadHashVersion;
        this.payload = Objects.requireNonNull(payload, "normalized payload 不能为 null");
        List<SensitiveValueEntry> sorted = new ArrayList<>(
            Objects.requireNonNull(sensitiveValues, "sensitiveValues 不能为 null（可为空列表）"));
        sorted.sort(Comparator.comparing(SensitiveValueEntry::valuePath));
        this.sensitiveValues = List.copyOf(sorted);
    }

    public static PayloadHashMaterial of(int payloadHashVersion, JsonNode payload) {
        return new PayloadHashMaterial(payloadHashVersion, payload, List.of());
    }

    public static PayloadHashMaterial of(int payloadHashVersion, JsonNode payload,
                                         List<SensitiveValueEntry> sensitiveValues) {
        return new PayloadHashMaterial(payloadHashVersion, payload, sensitiveValues);
    }

    /**
     * 计算 payload_hash（§47.1.2）。
     *
     * @return 64 位小写十六进制摘要
     */
    public String hash() {
        return SnapshotHasher.hash(payloadHashVersion, toCanonicalJson());
    }

    /**
     * 转为 canonical JSON 树（字段名固定，key 排序由 {@link CanonicalSerializer} 保证）。
     *
     * @return Hash Material 的 JSON 表达
     */
    public JsonNode toCanonicalJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("payloadHashVersion", payloadHashVersion);
        root.set("payload", payload);
        ArrayNode sensitive = root.putArray("sensitiveValues");
        for (SensitiveValueEntry entry : sensitiveValues) {
            ObjectNode e = sensitive.addObject();
            e.put("valuePath", entry.valuePath());
            e.put("sensitiveRef", entry.sensitiveRef());
            e.put("valueHmac", entry.valueHmac());
            e.put("hmacKeyVersion", entry.hmacKeyVersion());
        }
        return root;
    }

    /**
     * 单个敏感值条目（§47.1.2：按 value_path 排序的 sensitiveRef / value_hmac / hmac_key_version）。
     *
     * @param valuePath       敏感值在 payload 中的路径（排序键）
     * @param sensitiveRef    敏感值引用（§24.2 excel_import_sensitive_value.sensitive_value_id）
     * @param valueHmac       HMAC-SHA-256(secret, canonicalSensitiveValue)（§47.1.7）
     * @param hmacKeyVersion  HMAC Key 版本（&gt;0）
     */
    public record SensitiveValueEntry(String valuePath, String sensitiveRef,
                                      String valueHmac, int hmacKeyVersion) {

        public SensitiveValueEntry {
            Objects.requireNonNull(valuePath, "valuePath 不能为 null");
            Objects.requireNonNull(sensitiveRef, "sensitiveRef 不能为 null");
            Objects.requireNonNull(valueHmac, "valueHmac 不能为 null");
            if (hmacKeyVersion <= 0) {
                throw new IllegalArgumentException("hmacKeyVersion 必须 > 0");
            }
        }
    }
}
