package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 已确认手画户型的规划家具保存请求；只变更家具，不重新确认空间几何。
 *
 * @param expectedRevision 手画布局乐观锁版本
 * @param furniture 完整的规划家具实例列表
 */
public record ManualFloorPlanFurnitureSaveRequest(
    @NotNull @Min(0) Long expectedRevision,
    @NotNull @Size(max = 300) @Valid List<ManualFloorPlanLayoutRequest.Furniture> furniture
) {
}
