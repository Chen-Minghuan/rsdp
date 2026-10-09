package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 家具人工校正提交响应（V20，PUT /{analysisId}/furniture）。
 */
@Data
public class FloorPlanFurnitureEditResponse {

    private Integer analysisRevision;

    /** 提交后的 Furniture 对象版本。 */
    private Integer furnitureRevision;

    /** 保存后的 Revision 状态（EDITING/READY_TO_ACTIVATE，前端据此刷新激活入口）。 */
    private String revisionStatus;

    /** 保存后实时计算的激活就绪度（ready + issues，前端免额外 GET）。 */
    private FloorPlanActivationReadiness activationReadiness;

    /** 该 Revision 下最终生效家具列表。 */
    private List<FloorPlanFurnitureResponse> furniture;
}
