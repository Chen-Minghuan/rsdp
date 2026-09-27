package com.rsdp.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段级人工修正标记工具（设计文档 v3.3 §34.1，P0-D）。
 *
 * <p>RSPU / Variant / RSKU 当前实体各自保存权威的字段级标记
 * {@code manual_override_fields}（JSONB object）：</p>
 * <ul>
 *   <li>Key 使用稳定 JSON Pointer（如 {@code /factoryPrice}）；</li>
 *   <li>Value 只保存治理元数据 {@code {active, correctedBy, correctedAt, source}}，
 *       不复制敏感业务值；</li>
 *   <li>Excel / AI / 后台重试不能自行清除标记；恢复自动管理（active=false）是独立的
 *       授权人工操作；</li>
 *   <li>Merge / Identity Migration 必须合并标记；同一字段的活跃标记冲突时进入人工治理，
 *       不得静默丢弃（见 {@link #merge}）。</li>
 * </ul>
 *
 * <p>本类为纯函数工具，读写实体字段由调用方在同一数据库事务内完成。</p>
 */
public final class ManualOverrideFields {

    /** 修正来源：产品 UI 人工编辑。 */
    public static final String SOURCE_PRODUCT_UI = "product_ui";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ManualOverrideFields() {
    }

    /**
     * 一条字段级标记的治理元数据。
     *
     * @param active      是否活跃（活跃标记阻断 Excel/AI 覆盖）
     * @param correctedBy 修正人
     * @param correctedAt 修正时间（ISO-8601）
     * @param source      修正来源（如 product_ui）
     */
    public record Mark(boolean active, String correctedBy, String correctedAt, String source) {
    }

    /**
     * 归一化标记 JSON（null/空串/非法 JSON 一律视为空对象），用于变更检测比较。
     *
     * @param manualOverrideFields 原始 JSON
     * @return 规范形 JSON 字符串
     */
    public static String canonical(String manualOverrideFields) {
        return parse(manualOverrideFields).toString();
    }

    /**
     * 判断指定 JSON Pointer 字段是否存在活跃人工修正标记。
     *
     * @param manualOverrideFields 实体当前 manual_override_fields JSON（可为 null/空串）
     * @param jsonPointer          JSON Pointer（须以 {@code /} 开头）
     * @return 是否存在活跃标记
     */
    public static boolean isActive(String manualOverrideFields, String jsonPointer) {
        Mark mark = getMark(manualOverrideFields, jsonPointer);
        return mark != null && mark.active();
    }

    /**
     * 读取指定字段的标记；无标记或 JSON 非法时返回 null。
     *
     * @param manualOverrideFields 实体当前 manual_override_fields JSON（可为 null/空串）
     * @param jsonPointer          JSON Pointer
     * @return 标记；不存在返回 null
     */
    public static Mark getMark(String manualOverrideFields, String jsonPointer) {
        ObjectNode root = parse(manualOverrideFields);
        JsonNode node = root.get(jsonPointer);
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode active = node.get("active");
        return new Mark(
            active != null && active.asBoolean(false),
            textOrNull(node.get("correctedBy")),
            textOrNull(node.get("correctedAt")),
            textOrNull(node.get("source")));
    }

    /**
     * 列出全部活跃标记的 JSON Pointer（按字典序，便于确定性日志/审计）。
     *
     * @param manualOverrideFields 实体当前 manual_override_fields JSON（可为 null/空串）
     * @return 活跃字段指针列表
     */
    public static List<String> activePointers(String manualOverrideFields) {
        ObjectNode root = parse(manualOverrideFields);
        List<String> pointers = new ArrayList<>();
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String pointer = it.next();
            JsonNode node = root.get(pointer);
            if (node != null && node.isObject()) {
                JsonNode active = node.get("active");
                if (active != null && active.asBoolean(false)) {
                    pointers.add(pointer);
                }
            }
        }
        Collections.sort(pointers);
        return pointers;
    }

    /**
     * 标记字段为人工修正（active=true）。人工编辑业务值时，必须在同一数据库事务
     * 调用本方法设置标记并写 Audit Log（§34.1 生命周期）。
     *
     * @param manualOverrideFields 实体当前 manual_override_fields JSON（可为 null/空串）
     * @param jsonPointer          JSON Pointer（须以 {@code /} 开头）
     * @param correctedBy          修正人
     * @param source               修正来源（如 {@link #SOURCE_PRODUCT_UI}）
     * @return 更新后的 JSON
     */
    public static String markActive(String manualOverrideFields, String jsonPointer,
                                    String correctedBy, String source) {
        assertPointer(jsonPointer);
        ObjectNode root = parse(manualOverrideFields);
        ObjectNode mark = MAPPER.createObjectNode();
        mark.put("active", true);
        if (correctedBy != null) {
            mark.put("correctedBy", correctedBy);
        }
        mark.put("correctedAt", OffsetDateTime.now().toString());
        if (source != null) {
            mark.put("source", source);
        }
        root.set(jsonPointer, mark);
        return root.toString();
    }

    /**
     * 恢复自动管理（active=false）。独立的授权人工操作，须另行写审计（§34.1）。
     * 字段无标记时幂等返回原文。
     *
     * @param manualOverrideFields 实体当前 manual_override_fields JSON
     * @param jsonPointer          JSON Pointer
     * @param operator             操作人
     * @return 更新后的 JSON
     */
    public static String deactivate(String manualOverrideFields, String jsonPointer, String operator) {
        assertPointer(jsonPointer);
        ObjectNode root = parse(manualOverrideFields);
        JsonNode node = root.get(jsonPointer);
        if (node == null || !node.isObject()) {
            return root.toString();
        }
        ObjectNode mark = (ObjectNode) node;
        mark.put("active", false);
        if (operator != null) {
            mark.put("deactivatedBy", operator);
        }
        mark.put("deactivatedAt", OffsetDateTime.now().toString());
        return root.toString();
    }

    /**
     * Merge / Identity Migration 标记合并（§34.1）：以 target 为基底并入 source 的标记。
     *
     * <ul>
     *   <li>仅一侧存在的标记并入结果；</li>
     *   <li>两侧同字段均活跃 → 活跃冲突，返回结果的 {@link MergeResult#conflicts()} 非空，
     *       调用方必须进入人工治理（阻断或上报告决），不得静默丢弃；</li>
     *   <li>一侧活跃一侧已停用 → 活跃侧胜出（保留活跃标记）；两侧均停用 → 保留 target 侧。</li>
     * </ul>
     *
     * @param targetFields 合并目标（存续实体）的标记 JSON
     * @param sourceFields 被合并实体的标记 JSON
     * @return 合并结果（合并后 JSON + 活跃冲突字段清单）
     */
    public static MergeResult merge(String targetFields, String sourceFields) {
        ObjectNode target = parse(targetFields);
        ObjectNode source = parse(sourceFields);
        ObjectNode merged = target.deepCopy();
        List<String> conflicts = new ArrayList<>();
        Iterator<String> it = source.fieldNames();
        while (it.hasNext()) {
            String pointer = it.next();
            JsonNode sourceNode = source.get(pointer);
            JsonNode targetNode = merged.get(pointer);
            if (targetNode == null) {
                merged.set(pointer, sourceNode);
                continue;
            }
            boolean sourceActive = isActiveNode(sourceNode);
            boolean targetActive = isActiveNode(targetNode);
            if (sourceActive && targetActive) {
                conflicts.add(pointer);
            } else if (sourceActive) {
                merged.set(pointer, sourceNode);
            }
        }
        Collections.sort(conflicts);
        return new MergeResult(merged.toString(), List.copyOf(conflicts));
    }

    /**
     * 合并结果。
     *
     * @param mergedJson 合并后的标记 JSON（冲突字段保留 target 侧原值，等待人工治理）
     * @param conflicts  两侧均活跃的字段指针（升序）
     */
    public record MergeResult(String mergedJson, List<String> conflicts) {
        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }
    }

    private static boolean isActiveNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode active = node.get("active");
        return active != null && active.asBoolean(false);
    }

    private static ObjectNode parse(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (Exception e) {
            // 非法 JSON 按空标记处理（列有 jsonb_typeof CHECK 兜底，正常不会发生）
        }
        return MAPPER.createObjectNode();
    }

    private static void assertPointer(String jsonPointer) {
        if (jsonPointer == null || !jsonPointer.startsWith("/") || jsonPointer.length() < 2) {
            throw new IllegalArgumentException("manual_override_fields 的 Key 必须是稳定 JSON Pointer（以 / 开头）: "
                + jsonPointer);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /**
     * 调试/审计用：把标记 JSON 解析为指针 → 标记的 Map 视图（不暴露可变内部结构）。
     */
    public static Map<String, Mark> asMap(String manualOverrideFields) {
        ObjectNode root = parse(manualOverrideFields);
        Map<String, Mark> result = new LinkedHashMap<>();
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String pointer = it.next();
            Mark mark = getMark(manualOverrideFields, pointer);
            if (mark != null) {
                result.put(pointer, mark);
            }
        }
        return result;
    }
}
