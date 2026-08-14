package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 户型图空间搭配尺寸硬规则配置（{@code rsdp.floor-plan.rules}，户型图链路 v3.0 §4.7/§5.2）。
 *
 * <p>由 {@code util/RoomDimensionRules} 消费，可运维调整，不允许在规则引擎内硬编码。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.floor-plan.rules")
public class FloorPlanRulesProperties {

    /** R2：沙发长度 ≤ 沙发墙（开间）× 该比例。 */
    private double sofaWallRatio = 0.75;

    /** R3：沙发 → 茶几最小通道（mm），链式校验的硬下限。 */
    private int sofaTeaMinMm = 350;

    /** R3：沙发 → 茶几舒适通道上限（mm），预留给舒适度评分（P1）。 */
    private int sofaTeaMaxMm = 450;

    /** R2/R3：茶几 → 电视柜最小过人通道（mm）；R2 中同时作为沙发侧过道预留。 */
    private int walkwayMinMm = 600;

    /** 电视柜长度 ≤ 开间 × 该比例。 */
    private double tvCabinetWallRatio = 0.8;

    /** R1：小客厅面积分界（㎡，不含）。 */
    private int smallLivingAreaM2 = 12;

    /** R1：小客厅沙发长度上限（mm）。 */
    private int smallLivingSofaMaxMm = 2200;

    /** R1：大客厅面积分界（㎡，超过可推 L 型/组合）。 */
    private int largeLivingAreaM2 = 20;
}
