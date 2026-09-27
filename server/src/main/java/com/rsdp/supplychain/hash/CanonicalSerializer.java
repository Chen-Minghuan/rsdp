package com.rsdp.supplychain.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical JSON 序列化器（设计文档 §47.1.1 Canonical Hash Protocol）。
 *
 * <p>所有 payload_hash / snapshot_hash / mapping_hash / policy_hash 及
 * variantKey / quoteKey 的 Hash Material 都必须先经本类序列化为 canonical bytes，
 * 再计算 SHA-256，保证同一业务内容在重启 / 多节点间得到逐字节一致的结果。</p>
 *
 * <p>固定规则（对应 §47.1.1 十条）：</p>
 * <ol>
 *   <li>编码 UTF-8（{@link #canonicalBytes}）；</li>
 *   <li>Object Key 按 UTF-16 code unit 字典序固定排序（与 RFC 8785 JCS 同口径）；</li>
 *   <li>数字输出规范十进制文本：不使用科学计数法、去除无意义尾随 0、负零归一为 {@code 0}；
 *       NaN / Infinity 直接拒绝；</li>
 *   <li>Boolean 仅 {@code true / false}；</li>
 *   <li>Null 一律显式输出 {@code null}，是否允许由调用方 Schema 决定，序列化层不省略；</li>
 *   <li>String 原样输出（业务 Normalize 是调用方职责，本类不做 trim / case / NFC 处理），
 *       只做确定性 JSON 转义；</li>
 *   <li>Array 保持原有顺序，业务语义为集合时调用方须先排序；</li>
 *   <li>Timestamp 由调用方以 ISO-8601 UTC 字符串形式给出，序列化层不特殊处理；</li>
 *   <li>随机 IV 的 Ciphertext 不得进入 Hash Material（调用方职责，见 §47.1.2）；</li>
 *   <li>协议显式版本化：hashVersion 由调用方放入 Material（见 {@link SnapshotHasher}）。</li>
 * </ol>
 *
 * <p>Binary / POJO 节点没有稳定 JSON 表达，直接拒绝。</p>
 */
public final class CanonicalSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalSerializer() {
        // 工具类禁止实例化
    }

    /**
     * 把任意对象（Map / List / POJO / 标量）先转为 Jackson 树再做 canonical 序列化。
     *
     * @param value 任意可 JSON 化的对象，禁止 null
     * @return canonical JSON 文本
     */
    public static String canonical(Object value) {
        Objects.requireNonNull(value, "canonical 输入不能为 null");
        JsonNode node = value instanceof JsonNode json ? json : MAPPER.valueToTree(value);
        return canonical(node);
    }

    /**
     * 把 Jackson 树序列化为 canonical JSON 文本。
     *
     * @param node JSON 树，禁止 null
     * @return canonical JSON 文本
     */
    public static String canonical(JsonNode node) {
        Objects.requireNonNull(node, "canonical 输入不能为 null");
        StringBuilder sb = new StringBuilder(256);
        append(node, sb);
        return sb.toString();
    }

    /**
     * 把 Jackson 树序列化为 canonical UTF-8 字节（Hash 的直接输入）。
     *
     * @param node JSON 树，禁止 null
     * @return canonical UTF-8 字节
     */
    public static byte[] canonicalBytes(JsonNode node) {
        return canonical(node).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 便捷入口：Map 结构直接转 canonical UTF-8 字节。
     *
     * @param map 任意 Map，key 会按字典序重排
     * @return canonical UTF-8 字节
     */
    public static byte[] canonicalBytes(Map<String, ?> map) {
        Objects.requireNonNull(map, "canonical 输入不能为 null");
        return canonicalBytes((JsonNode) MAPPER.valueToTree(map));
    }

    /**
     * 数字的规范十进制文本：去尾随 0、禁科学计数法、负零归一（§47.1.1 第 3 条）。
     * variantKey / quoteKey 以外的场景（如 v2 Fingerprint 的 MM 尺寸段）也复用本方法。
     *
     * @param value 十进制值，禁止 null
     * @return 规范十进制文本
     */
    public static String canonicalDecimal(BigDecimal value) {
        Objects.requireNonNull(value, "数字不能为 null");
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static void append(JsonNode node, StringBuilder sb) {
        if (node instanceof ObjectNode obj) {
            appendObject(obj, sb);
        } else if (node instanceof ArrayNode arr) {
            appendArray(arr, sb);
        } else if (node.isNull()) {
            sb.append("null");
        } else if (node.isTextual()) {
            appendString(node.textValue(), sb);
        } else if (node.isNumber()) {
            sb.append(canonicalNumber(node));
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else {
            throw new IllegalArgumentException(
                "不支持的 JSON 节点类型，无法 canonical 序列化: " + node.getNodeType());
        }
    }

    private static void appendObject(ObjectNode obj, StringBuilder sb) {
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        sb.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(key, sb);
            sb.append(':');
            append(obj.get(key), sb);
        }
        sb.append('}');
    }

    private static void appendArray(ArrayNode arr, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (JsonNode element : arr) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            append(element, sb);
        }
        sb.append(']');
    }

    private static String canonicalNumber(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            double d = node.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("NaN / Infinity 不允许进入 Hash Material");
            }
        }
        return canonicalDecimal(node.decimalValue());
    }

    /**
     * 确定性 JSON 字符串转义：仅转义引号、反斜杠与 &lt;0x20 控制字符，
     * 其余字符（含非 BMP / 中文）原样输出，保证跨语言可复现。
     */
    private static void appendString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u");
                        sb.append(Character.forDigit((c >> 12) & 0xF, 16));
                        sb.append(Character.forDigit((c >> 8) & 0xF, 16));
                        sb.append(Character.forDigit((c >> 4) & 0xF, 16));
                        sb.append(Character.forDigit(c & 0xF, 16));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
