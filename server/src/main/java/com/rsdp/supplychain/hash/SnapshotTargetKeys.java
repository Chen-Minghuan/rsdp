package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.util.ContentHashes;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Preview Snapshot 稳定目标键生成器（设计文档 §24.1 Issue Target Protocol，
 * §85 收口第 17 条）。
 *
 * <p>一行展开多 Variant / 一列展开多 Quote 时必须使用目标键而非源单元格坐标决定阻断对象：</p>
 *
 * <pre>
 * variantKey    = "VAR:"         + SHA-256(canonical({fingerprintVersion, identity}))
 * quoteKey      = "QUOTE:"       + SHA-256(canonical({variantKey, factoryCode,
 *                 supplierConfigurationKey, currencyCode, priceUnit, effectiveFrom}))
 * targetRskuKey = "RSKU_TARGET:" + SHA-256(canonical({variantKey, factoryCode,
 *                 supplierConfigurationKey}))
 * </pre>
 *
 * <p>canonical 复用 §47.1 UTF-8 Canonical JSON 规则（{@link CanonicalSerializer}），
 * 固定 key 排序、显式 null；禁止无分隔符字符串拼接。所有输入字段以命名参数 / Map
 * 传入，键的生成与字段书写顺序无关。同一逻辑 Quote 重复出现必然得到同一 quoteKey
 * （不掺入源坐标）；是否合并由 §38.1 业务比较决定，与本类无关。</p>
 */
public final class SnapshotTargetKeys {

    /**
     * variantKey 前缀。
     */
    public static final String VARIANT_PREFIX = "VAR:";
    /**
     * quoteKey 前缀。
     */
    public static final String QUOTE_PREFIX = "QUOTE:";
    /**
     * targetRskuKey 前缀。
     */
    public static final String RSKU_TARGET_PREFIX = "RSKU_TARGET:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SnapshotTargetKeys() {
        // 工具类禁止实例化
    }

    // ---------------------------------------------------------------- variantKey

    /**
     * v1（Legacy）身份下的 variantKey：identity 为三段 COALESCE 结果
     * （{@code {size, color, material}}，与旧索引同口径）。
     *
     * @return 稳定 variantKey
     */
    public static String variantKeyV1(String sizeCode, String sizeText,
                                      String colorCode, String colorText,
                                      String materialCode, String materialText) {
        ObjectNode identity = MAPPER.createObjectNode();
        identity.put("size", VariantFingerprints.coalesce(sizeCode, sizeText));
        identity.put("color", VariantFingerprints.coalesce(colorCode, colorText));
        identity.put("material", VariantFingerprints.coalesce(materialCode, materialText));
        return variantKey(VariantFingerprints.LEGACY_VERSION, identity);
    }

    /**
     * v2+ 身份下的 variantKey：identity 为「字段名 → CODE/TEXT/MM/ABSENT 序列化值」，
     * Map 顺序无关（canonical JSON 固定 key 排序）。
     *
     * @param fingerprintVersion 必须 &gt;= 2
     * @param fields             identity 字段
     * @return 稳定 variantKey
     */
    public static String variantKeyV2(int fingerprintVersion,
                                      Map<String, VariantFingerprints.IdentityValue> fields) {
        if (fingerprintVersion < 2) {
            throw new IllegalArgumentException("v2 身份协议要求 fingerprintVersion >= 2");
        }
        Objects.requireNonNull(fields, "identity 字段不能为 null");
        TreeMap<String, VariantFingerprints.IdentityValue> sorted = new TreeMap<>(fields);
        ObjectNode identity = MAPPER.createObjectNode();
        sorted.forEach((name, value) -> identity.put(name, value.serialize()));
        return variantKey(fingerprintVersion, identity);
    }

    /**
     * 通用 variantKey（§24.1）：{@code "VAR:" + SHA-256(canonical({fingerprintVersion, identity}))}。
     *
     * @param fingerprintVersion 身份协议版本（&gt;0）
     * @param canonicalIdentity  canonical 身份（Jackson 树）
     * @return 稳定 variantKey
     */
    public static String variantKey(int fingerprintVersion,
                                    com.fasterxml.jackson.databind.JsonNode canonicalIdentity) {
        if (fingerprintVersion <= 0) {
            throw new IllegalArgumentException("fingerprintVersion 必须 > 0");
        }
        Objects.requireNonNull(canonicalIdentity, "identity 不能为 null");
        ObjectNode material = MAPPER.createObjectNode();
        material.put("fingerprintVersion", fingerprintVersion);
        material.set("identity", canonicalIdentity);
        return VARIANT_PREFIX + ContentHashes.sha256Hex(CanonicalSerializer.canonicalBytes(material));
    }

    // ---------------------------------------------------------------- quoteKey

    /**
     * quoteKey（§24.1）。所有字段以命名参数传入；{@code effectiveFrom} 允许为 null
     * 并以显式 {@code null} 进入 canonical JSON（无日期报价与有日期报价是不同的键）。
     *
     * @param variantKey                同 Snapshot 内已存在的 variantKey
     * @param factoryCode               工厂码
     * @param supplierConfigurationKey  §18.3 Resolver 解析后的供应商配置键（或候选键）
     * @param currencyCode              币种（Quote 基础口径之一）
     * @param priceUnit                 计价单位（Quote 基础口径之一）
     * @param effectiveFrom             生效日期（ISO-8601，可空）
     * @return 稳定 quoteKey
     */
    public static String quoteKey(String variantKey, String factoryCode, String supplierConfigurationKey,
                                  String currencyCode, String priceUnit, String effectiveFrom) {
        ObjectNode material = MAPPER.createObjectNode();
        material.put("variantKey", requireText(variantKey, "variantKey"));
        material.put("factoryCode", requireText(factoryCode, "factoryCode"));
        material.put("supplierConfigurationKey",
            requireText(supplierConfigurationKey, "supplierConfigurationKey"));
        material.put("currencyCode", requireText(currencyCode, "currencyCode"));
        material.put("priceUnit", requireText(priceUnit, "priceUnit"));
        if (effectiveFrom == null) {
            material.putNull("effectiveFrom");
        } else {
            material.put("effectiveFrom", effectiveFrom);
        }
        return QUOTE_PREFIX + ContentHashes.sha256Hex(CanonicalSerializer.canonicalBytes(material));
    }

    /**
     * targetRskuKey（§24.1）：Quote 最终竞争的 RSKU 身份，只含 variant / factory /
     * supplierConfiguration 三元组，不含币种、单位、日期（因此同一 RSKU 上基础口径
     * 不同的多份 Quote 共享同一 targetRskuKey，由上层据此检测
     * {@code MULTIPLE_CURRENT_QUOTES_FOR_RSKU}）。
     *
     * @return 稳定 targetRskuKey
     */
    public static String targetRskuKey(String variantKey, String factoryCode,
                                       String supplierConfigurationKey) {
        ObjectNode material = MAPPER.createObjectNode();
        material.put("variantKey", requireText(variantKey, "variantKey"));
        material.put("factoryCode", requireText(factoryCode, "factoryCode"));
        material.put("supplierConfigurationKey",
            requireText(supplierConfigurationKey, "supplierConfigurationKey"));
        return RSKU_TARGET_PREFIX + ContentHashes.sha256Hex(CanonicalSerializer.canonicalBytes(material));
    }

    // ---------------------------------------------------------------- field target

    /**
     * field 目标键：{@code <ownerTargetKey>#<json-pointer>}（§24.1）。
     * owner 只能是 {@code GROUP:<groupId>}、{@code VAR:<hash>} 或 {@code QUOTE:<hash>}，
     * 例如 {@code VAR:...#/dimensions/widthMm}。owner 在 Snapshot / Group 中的存在性
     * 校验由服务层负责，本类只做形态校验。
     *
     * @param ownerTargetKey owner 目标键（GROUP/VAR/QUOTE 前缀）
     * @param jsonPointer    JSON Pointer（以 {@code /} 开头）
     * @return field 目标键
     */
    public static String fieldTargetKey(String ownerTargetKey, String jsonPointer) {
        String owner = requireText(ownerTargetKey, "ownerTargetKey");
        if (!owner.startsWith("GROUP:") && !owner.startsWith(VARIANT_PREFIX)
            && !owner.startsWith(QUOTE_PREFIX)) {
            throw new IllegalArgumentException(
                "field owner 只能是 GROUP: / VAR: / QUOTE: 前缀: " + owner);
        }
        String pointer = requireText(jsonPointer, "jsonPointer");
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("jsonPointer 必须以 / 开头: " + pointer);
        }
        return owner + '#' + pointer;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
