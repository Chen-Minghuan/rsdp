package com.rsdp.agent.domain;

import com.rsdp.agent.patch.RequirementConstraints;
import org.springframework.stereotype.Component;

/**
 * 有效约束合并器：需求约束 + 户型空间事实 → 最终检索条件。
 *
 * <p>约束优先级（户型驱动文档 §17）：空间物理硬约束 &gt; 用户主动设置的更严格约束 &gt; 用户偏好。</p>
 *
 * <p>v1 空间硬约束口径（确定性，可调）：房间开间/进深已知时，
 * 家具宽度上限 = 开间 - 600mm（通道预留），进深上限 = 进深 - 600mm；
 * 用户报了更严格的尺寸时取更小值。房间尺寸未知时不施加空间约束（退化为纯需求过滤）。</p>
 */
@Component
public class EffectiveConstraintComposer {

    /** 通道预留（mm）：主家具与对面墙/家具之间的最小通行宽度。 */
    public static final int WALKWAY_RESERVE_MM = 600;

    /**
     * 合并生成检索条件。
     *
     * @param constraints 有效需求约束（全局 + 当前房间已合并的视图，可空）
     * @param activeRoom  当前房间事实（可空=无户型会话）
     * @return 检索条件（sofaForm/areaM2/note 等不下推，留给推荐理由参考）
     */
    public ProductSearchCriteria compose(RequirementConstraints constraints, FloorPlanRoomFact activeRoom) {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        if (constraints != null) {
            criteria.setCategoryCode(constraints.getCategoryCode());
            criteria.setStyle(constraints.getStyle());
            criteria.setMaterial(constraints.getMaterial());
            criteria.setColor(constraints.getColor());
            criteria.setBudgetMax(constraints.getBudgetMax());
            criteria.setMaxWidthMm(constraints.getMaxWidthMm());
            criteria.setMinWidthMm(constraints.getMinWidthMm());
        }
        if (activeRoom != null) {
            // 空间物理硬约束：只收紧不放宽（用户更严格的约束优先）
            Integer spaceWidthCap = spaceCap(activeRoom.widthMm());
            if (spaceWidthCap != null) {
                criteria.setMaxWidthMm(minNullable(criteria.getMaxWidthMm(), spaceWidthCap));
            }
            Integer spaceDepthCap = spaceCap(activeRoom.depthMm());
            if (spaceDepthCap != null) {
                criteria.setMaxDepthMm(spaceDepthCap);
            }
            criteria.setRoomType(activeRoom.roomType());
            criteria.setRoomAreaM2(activeRoom.areaM2());
        }
        return criteria;
    }

    /**
     * 空间尺寸是否齐备到足以替代"沙发形态或尺寸限制"这一关键约束面。
     *
     * @param activeRoom 当前房间事实（可空）
     * @return true 表示空间宽度已知（硬约束由户型提供，无需用户再报尺寸）
     */
    public boolean roomProvidesSizeConstraint(FloorPlanRoomFact activeRoom) {
        return activeRoom != null && spaceCap(activeRoom.widthMm()) != null;
    }

    /** 空间上限：维度已知且扣掉通道预留后仍为正值时返回，否则 null（不施加约束）。 */
    private Integer spaceCap(Integer dimensionMm) {
        if (dimensionMm == null) {
            return null;
        }
        int cap = dimensionMm - WALKWAY_RESERVE_MM;
        return cap > 0 ? cap : null;
    }

    private Integer minNullable(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }
}
