package com.rsdp.agent.patch;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 需求 Patch Reducer（无状态）。
 *
 * <p>架构铁律：需求更新 = LLM 输出 operations，Java Reducer 负责真正修改，
 * 不让模型重生成完整档案。所有应用规则集中在此：</p>
 * <ul>
 *   <li>field 必须在 {@link RequirementConstraints} 字段白名单内，否则拒绝；</li>
 *   <li>set：evidence 必须非空白（用户原话依据）；value 按字段类型转换，失败拒绝；</li>
 *   <li>clear：清除该字段；</li>
 *   <li>任何单条 operation 被拒绝不影响其余 operation 应用（部分成功语义）。</li>
 * </ul>
 */
@Slf4j
@Component
public class RequirementPatchReducer {

    /** set 操作。 */
    public static final String OP_SET = "set";

    /** clear 操作。 */
    public static final String OP_CLEAR = "clear";

    /** 字段白名单（RequirementConstraints 声明的实例字段名，反射一次避免漂移；排除 MAPPER/log 等静态字段）。 */
    private static final Set<String> FIELD_WHITELIST = Arrays.stream(
            RequirementConstraints.class.getDeclaredFields())
        .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
        .map(java.lang.reflect.Field::getName)
        .collect(Collectors.toUnmodifiableSet());

    /** Patch 应用结果。 */
    @Data
    public static class ApplyResult {

        /** 应用后的需求约束（新对象，入参 base 不被修改）。 */
        private RequirementConstraints updated;

        /** 成功应用的 operation 数。 */
        private int appliedCount;

        /** 被拒绝的 operation 及原因。 */
        private List<RejectedOperation> rejectedOperations = new ArrayList<>();
    }

    /** 被拒绝的 operation 及拒绝原因。 */
    public record RejectedOperation(PatchOperation operation, String reason) {
    }

    /**
     * 将 Patch 应用到需求约束上。
     *
     * @param base  当前需求约束（不被修改；null 按空档案处理）
     * @param patch 需求 Patch（null/空 operations 返回 base 副本）
     * @return 应用结果（更新后档案 + 应用/拒绝明细）
     */
    public ApplyResult apply(RequirementConstraints base, RequirementPatch patch) {
        ApplyResult result = new ApplyResult();
        RequirementConstraints updated = base != null ? base.copy() : new RequirementConstraints();
        result.setUpdated(updated);
        if (patch == null || patch.getOperations() == null || patch.getOperations().isEmpty()) {
            return result;
        }
        for (PatchOperation operation : patch.getOperations()) {
            String rejection = applyOne(updated, operation);
            if (rejection == null) {
                result.setAppliedCount(result.getAppliedCount() + 1);
            } else {
                log.info("需求 Patch operation 被拒绝：field={}, operation={}, reason={}",
                    operation != null ? operation.getField() : null,
                    operation != null ? operation.getOperation() : null, rejection);
                result.getRejectedOperations().add(new RejectedOperation(operation, rejection));
            }
        }
        return result;
    }

    /**
     * 应用单条 operation。
     *
     * @return 拒绝原因；成功返回 null
     */
    private String applyOne(RequirementConstraints target, PatchOperation operation) {
        if (operation == null || !StringUtils.hasText(operation.getField())) {
            return "field 为空";
        }
        String field = operation.getField().trim();
        if (!FIELD_WHITELIST.contains(field)) {
            return "field 不在白名单内: " + field;
        }
        String op = operation.getOperation() == null ? "" : operation.getOperation().trim();
        try {
            return switch (op) {
                case OP_SET -> applySet(target, field, operation);
                case OP_CLEAR -> applyClear(target, field);
                default -> "未知 operation: " + op;
            };
        } catch (IllegalArgumentException e) {
            // 双保险：白名单与 setter 路由口径不一致时单条拒绝，不中断整批
            return "field 路由失败: " + e.getMessage();
        }
    }

    /** 应用 clear：字段当前无值时视为无操作拒绝，避免产生无实际变更的噪声版本。 */
    private String applyClear(RequirementConstraints target, String field) {
        if (getter(field).apply(target) == null) {
            return "clear 的字段当前未设置: " + field;
        }
        setter(field).accept(target, null);
        return null;
    }

    /** 应用 set：evidence 非空校验 + 按字段类型转换。 */
    private String applySet(RequirementConstraints target, String field, PatchOperation operation) {
        if (!StringUtils.hasText(operation.getEvidence())) {
            return "set 缺少 evidence（用户原话依据）";
        }
        if (!StringUtils.hasText(operation.getValue())) {
            return "set 缺少 value";
        }
        String raw = operation.getValue().trim();
        Object converted;
        try {
            converted = convert(field, raw);
        } catch (IllegalArgumentException e) {
            return "value 类型转换失败: " + raw + " -> " + e.getMessage();
        }
        @SuppressWarnings("unchecked")
        BiConsumer<RequirementConstraints, Object> setter = (BiConsumer<RequirementConstraints, Object>) setter(field);
        setter.accept(target, converted);
        return null;
    }

    /** 按字段类型转换 value。 */
    private Object convert(String field, String raw) {
        return switch (field) {
            case "budgetMax", "areaM2" -> {
                try {
                    yield new BigDecimal(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("BigDecimal", e);
                }
            }
            case "maxWidthMm", "minWidthMm" -> {
                try {
                    yield Integer.valueOf(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Integer", e);
                }
            }
            default -> raw;
        };
    }

    /** 字段 getter 路由（与 setter 一一对应，供 clear 判空用）。 */
    private java.util.function.Function<RequirementConstraints, Object> getter(String field) {
        return switch (field) {
            case "categoryCode" -> RequirementConstraints::getCategoryCode;
            case "categoryName" -> RequirementConstraints::getCategoryName;
            case "style" -> RequirementConstraints::getStyle;
            case "material" -> RequirementConstraints::getMaterial;
            case "color" -> RequirementConstraints::getColor;
            case "budgetMax" -> RequirementConstraints::getBudgetMax;
            case "maxWidthMm" -> RequirementConstraints::getMaxWidthMm;
            case "minWidthMm" -> RequirementConstraints::getMinWidthMm;
            case "sofaForm" -> RequirementConstraints::getSofaForm;
            case "areaM2" -> RequirementConstraints::getAreaM2;
            case "note" -> RequirementConstraints::getNote;
            default -> throw new IllegalArgumentException("field 不在白名单内: " + field);
        };
    }

    /** 字段 setter 路由（白名单内字段一一对应，避免反射写权限问题）。 */
    private BiConsumer<RequirementConstraints, Object> setter(String field) {
        return switch (field) {
            case "categoryCode" -> (t, v) -> t.setCategoryCode((String) v);
            case "categoryName" -> (t, v) -> t.setCategoryName((String) v);
            case "style" -> (t, v) -> t.setStyle((String) v);
            case "material" -> (t, v) -> t.setMaterial((String) v);
            case "color" -> (t, v) -> t.setColor((String) v);
            case "budgetMax" -> (t, v) -> t.setBudgetMax((BigDecimal) v);
            case "maxWidthMm" -> (t, v) -> t.setMaxWidthMm((Integer) v);
            case "minWidthMm" -> (t, v) -> t.setMinWidthMm((Integer) v);
            case "sofaForm" -> (t, v) -> t.setSofaForm((String) v);
            case "areaM2" -> (t, v) -> t.setAreaM2((BigDecimal) v);
            case "note" -> (t, v) -> t.setNote((String) v);
            default -> throw new IllegalArgumentException("field 不在白名单内: " + field);
        };
    }
}
