package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 无图纸手画布局完整快照保存请求。
 *
 * @param expectedRevision 客户端读取到的乐观锁版本
 * @param layout 完整布局快照
 */
public record ManualFloorPlanLayoutRequest(
    @NotNull(message = "布局版本不能为空")
    @Min(value = 0, message = "布局版本不能为负数")
    Long expectedRevision,

    @NotNull(message = "布局数据不能为空")
    @Valid
    Layout layout
) {

    /**
     * 手画布局快照。
     *
     * @param version 数据结构版本
     * @param canvas 画布参数
     * @param rooms 空间列表，草稿允许为空
     */
    public record Layout(
        @NotNull(message = "布局结构版本不能为空")
        @Min(value = 1, message = "布局结构版本必须从 1 开始")
        Integer version,

        @NotNull(message = "画布参数不能为空")
        @Valid
        Canvas canvas,

        @NotNull(message = "空间列表不能为空")
        @Size(max = 50, message = "单个布局最多支持 50 个空间")
        List<@Valid Room> rooms
    ) {
    }

    /**
     * 毫米坐标画布。
     *
     * @param widthMm 画布宽度
     * @param heightMm 画布高度
     * @param gridMm 网格吸附步长
     */
    public record Canvas(
        @NotNull(message = "画布宽度不能为空")
        @Min(value = 1000, message = "画布宽度不能小于 1000mm")
        @Max(value = 100000, message = "画布宽度不能超过 100000mm")
        Integer widthMm,

        @NotNull(message = "画布高度不能为空")
        @Min(value = 1000, message = "画布高度不能小于 1000mm")
        @Max(value = 100000, message = "画布高度不能超过 100000mm")
        Integer heightMm,

        @NotNull(message = "网格尺寸不能为空")
        @Min(value = 10, message = "网格尺寸不能小于 10mm")
        @Max(value = 1000, message = "网格尺寸不能超过 1000mm")
        Integer gridMm
    ) {
    }

    /**
     * 单个手画空间。
     *
     * @param clientId 前端稳定 ID
     * @param label 空间名称
     * @param roomType room_type 字典码
     * @param parts 可编辑矩形组成块
     * @param placementZone 主布置矩形；为空时后端取面积最大的组成块
     * @param placementZones 全部布置区，首个为主布置区；旧草稿可省略
     */
    public record Room(
        @NotBlank(message = "空间临时 ID 不能为空")
        @Size(max = 64, message = "空间临时 ID 长度不能超过 64")
        String clientId,

        @Size(max = 128, message = "空间名称长度不能超过 128")
        String label,

        @NotBlank(message = "空间类型不能为空")
        @Size(max = 32, message = "空间类型长度不能超过 32")
        String roomType,

        @NotEmpty(message = "每个空间至少需要一个形状块")
        @Size(max = 32, message = "单个空间最多支持 32 个形状块")
        List<@Valid Rect> parts,

        @Valid
        Rect placementZone,

        @Size(max = 32, message = "单个空间最多支持 32 个布置区")
        List<@Valid Rect> placementZones
    ) {
    }

    /**
     * 毫米坐标矩形。
     *
     * @param id 组成块 ID；placementZone 可空
     * @param x 左上角 X
     * @param y 左上角 Y
     * @param w 宽度
     * @param h 高度
     */
    public record Rect(
        @Size(max = 64, message = "形状块 ID 长度不能超过 64")
        String id,

        @NotNull(message = "矩形 x 坐标不能为空")
        @Min(value = 0, message = "矩形 x 坐标不能为负数")
        Integer x,

        @NotNull(message = "矩形 y 坐标不能为空")
        @Min(value = 0, message = "矩形 y 坐标不能为负数")
        Integer y,

        @NotNull(message = "矩形宽度不能为空")
        @Min(value = 1, message = "矩形宽度必须为正数")
        Integer w,

        @NotNull(message = "矩形高度不能为空")
        @Min(value = 1, message = "矩形高度必须为正数")
        Integer h
    ) {
    }
}
