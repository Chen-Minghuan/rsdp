package com.rsdp.agent.domain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.common.ReviewStatus;
import com.rsdp.entity.RspuMaster;
import com.rsdp.security.SecurityOperatorContext;
import org.springframework.stereotype.Component;

/**
 * 产品可见性/审核过滤策略（营销 Agent 唯一收口点）。
 *
 * <p>架构铁律：权限/审核过滤集中在这一处，Agent/Tool 的任何产品查询都必须经过
 * {@link #applyScope(QueryWrapper)}，不允许各自私写过滤条件，防止口径漂移。</p>
 *
 * <p>口径与 RetrievalService / PricingPreviewService 保持一致：</p>
 * <ul>
 *   <li>{@code deleted_at} 逻辑删除由 MyBatis-Plus {@code @TableLogic} 自动追加，此处不重复；</li>
 *   <li>{@code status = 'active'}（在售）；</li>
 *   <li>非平台运营人员（非 ADMIN/EDITOR）强制 {@code review_status = '已确认'}
 *       （{@link ReviewStatus#APPROVED} 的数据库存储值）。</li>
 * </ul>
 */
@Component
public class ProductVisibilityPolicy {

    /** 在售状态存储值（rspu_master.status，与 PricingPreviewService/FloorPlanMatchingService 同口径）。 */
    public static final String STATUS_ACTIVE = "active";

    /**
     * 将可见性范围下推到查询条件。
     *
     * @param wrapper 待追加条件的查询构造器
     */
    public void applyScope(QueryWrapper<RspuMaster> wrapper) {
        wrapper.eq("status", STATUS_ACTIVE);
        if (!SecurityOperatorContext.isPlatformStaff()) {
            wrapper.eq("review_status", ReviewStatus.APPROVED.getDbValue());
        }
    }
}
