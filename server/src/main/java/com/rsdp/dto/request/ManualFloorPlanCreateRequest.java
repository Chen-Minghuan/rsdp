package com.rsdp.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 无图纸手画布局建单请求。
 *
 * @param canvasWidthMm 画布宽度（毫米）
 * @param canvasHeightMm 画布高度（毫米）
 * @param gridMm 网格吸附步长（毫米）
 * @param projectId 归属项目 ID，可空
 * @param sourceName 户型名称，可空
 */
public record ManualFloorPlanCreateRequest(
    @NotNull(message = "画布宽度不能为空")
    @Min(value = 1000, message = "画布宽度不能小于 1000mm")
    @Max(value = 100000, message = "画布宽度不能超过 100000mm")
    Integer canvasWidthMm,

    @NotNull(message = "画布高度不能为空")
    @Min(value = 1000, message = "画布高度不能小于 1000mm")
    @Max(value = 100000, message = "画布高度不能超过 100000mm")
    Integer canvasHeightMm,

    @NotNull(message = "网格尺寸不能为空")
    @Min(value = 10, message = "网格尺寸不能小于 10mm")
    @Max(value = 1000, message = "网格尺寸不能超过 1000mm")
    Integer gridMm,

    @Size(max = 64, message = "项目 ID 长度不能超过 64")
    String projectId,

    @Size(max = 128, message = "户型名称长度不能超过 128")
    String sourceName
) {
}
