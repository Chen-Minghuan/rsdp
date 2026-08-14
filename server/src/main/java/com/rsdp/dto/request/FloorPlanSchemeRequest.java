package com.rsdp.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 户型图搭配方案生成请求（管理端接口 4，户型图链路 v3.0 §4.2；P2 起支持多空间）。
 */
@Data
public class FloorPlanSchemeRequest {

    /**
     * 目标空间 ID（单空间模式，必须为该分析批次下的已确认空间）。
     * 与 {@link #roomIds} 至少填一个；roomIds 非空时本字段忽略。
     */
    private String roomId;

    /**
     * 目标空间 ID 列表（多空间批量搭配，v3.0 §8 P2）：非空时逐空间按各自模板规则
     * 生成候选与 LLM 终审，合并落一个 scheme + scheme_item。
     */
    @Size(max = 9, message = "多空间搭配一次最多选择 9 个空间")
    private List<@NotBlank(message = "空间 ID 不能为空") String> roomIds;

    /** 风格偏好（风格字典码），可空。 */
    private String stylePreference;

    /** 预算上限（元），可空。 */
    @Min(value = 0, message = "预算上限不能为负数")
    private BigDecimal budgetLimit;

    /** 所属设计项目 ID，可空。 */
    private String projectId;

    /**
     * 沙发墙朝向（户型图链路 v3.0 §8 P1）：width=开间方向墙（默认，缺省/null 按 width 处理）、
     * depth=进深方向墙。影响 R2 沙发/电视柜长度上限的墙长取值与 R3 链式校验方向（仅客厅空间生效）。
     */
    @Pattern(regexp = "width|depth", message = "沙发墙朝向仅支持 width（开间方向墙）或 depth（进深方向墙）")
    private String sofaWall;

    /** roomId 与 roomIds 至少填一个（Bean Validation 交叉校验）。 */
    @JsonIgnore
    @AssertTrue(message = "roomId 与 roomIds 至少填一个")
    public boolean isRoomSelectionValid() {
        return StringUtils.hasText(roomId)
            || (roomIds != null && roomIds.stream().anyMatch(StringUtils::hasText));
    }
}
