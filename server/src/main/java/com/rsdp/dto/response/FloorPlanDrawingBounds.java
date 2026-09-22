package com.rsdp.dto.response;

import lombok.Data;

/**
 * CAD 图纸外包络（毫米坐标系范围，CAD 户型导入增强·图片+CAD 双文件通道）。
 *
 * <p>前端契约，字段名不可改：前端用它把 rooms[].polygon 毫米坐标归一化后叠加到底图上。
 * 视觉识别通道恒为 null。</p>
 */
@Data
public class FloorPlanDrawingBounds {

    private Double minX;
    private Double minY;
    private Double maxX;
    private Double maxY;
}
