package com.rsdp.agent.patch;

import java.util.Set;

/**
 * 需求作用域（W2 户型驱动）：全局（全屋偏好）vs 房间（当前空间专属）。
 *
 * <p>无户型会话所有字段都在全局作用域（历史行为不变）；绑定户型且有当前房间时，
 * 房间字段落入该房间作用域，全局字段仍落全局。有效约束 = 全局为底 + 房间字段覆盖
 * （房间字段为 null 时继承全局值，即房间作用域 clear = 取消房间特化）。</p>
 */
public final class RequirementScope {

    private RequirementScope() {
    }

    /** 全局字段：全屋偏好（风格/总预算/备注）。 */
    public static final Set<String> GLOBAL_FIELDS = Set.of("style", "budgetMax", "note");

    /** 房间字段：当前空间专属（品类/材质/颜色/尺寸/形态/面积）。 */
    public static final Set<String> ROOM_FIELDS = Set.of(
        "categoryCode", "categoryName", "material", "color",
        "maxWidthMm", "minWidthMm", "sofaForm", "areaM2");

    /** 字段是否属于房间作用域。 */
    public static boolean isRoomField(String field) {
        return field != null && ROOM_FIELDS.contains(field.trim());
    }

    /** 字段是否属于全局作用域。 */
    public static boolean isGlobalField(String field) {
        return field != null && GLOBAL_FIELDS.contains(field.trim());
    }

    /**
     * 合并有效约束：全局为底，房间作用域的非空房间字段覆盖。
     *
     * @param global 全局作用域约束（可空）
     * @param room   当前房间作用域约束（可空）
     * @return 合并后的新对象
     */
    public static RequirementConstraints mergeEffective(RequirementConstraints global, RequirementConstraints room) {
        RequirementConstraints merged = global != null ? global.copy() : new RequirementConstraints();
        if (room == null) {
            return merged;
        }
        if (room.getCategoryCode() != null) {
            merged.setCategoryCode(room.getCategoryCode());
        }
        if (room.getCategoryName() != null) {
            merged.setCategoryName(room.getCategoryName());
        }
        if (room.getMaterial() != null) {
            merged.setMaterial(room.getMaterial());
        }
        if (room.getColor() != null) {
            merged.setColor(room.getColor());
        }
        if (room.getMaxWidthMm() != null) {
            merged.setMaxWidthMm(room.getMaxWidthMm());
        }
        if (room.getMinWidthMm() != null) {
            merged.setMinWidthMm(room.getMinWidthMm());
        }
        if (room.getSofaForm() != null) {
            merged.setSofaForm(room.getSofaForm());
        }
        if (room.getAreaM2() != null) {
            merged.setAreaM2(room.getAreaM2());
        }
        return merged;
    }
}
