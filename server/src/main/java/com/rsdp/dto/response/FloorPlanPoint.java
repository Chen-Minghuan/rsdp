package com.rsdp.dto.response;

import lombok.Data;

/** CAD 毫米坐标点，用于空间标签的内部锚点。 */
@Data
public class FloorPlanPoint {
    private Double x;
    private Double y;
}
