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
     * @param furniture 规划家具实例；旧版布局可缺省
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
        List<@Valid Room> rooms,

        @Size(max = 300, message = "单个布局最多支持 300 件规划家具")
        List<@Valid Furniture> furniture
    ) {
        /** 兼容旧版服务和测试中不含规划家具的布局构造。 */
        public Layout(Integer version, Canvas canvas, List<Room> rooms) {
            this(version, canvas, rooms, null);
        }
    }

    /**
     * 毫米坐标画布。
     *
     * @param widthMm 旧版画布宽度；无限画布模式中的起始视野宽度
     * @param heightMm 旧版画布高度；无限画布模式中的起始视野高度
     * @param gridMm 网格吸附步长
     * @param mode unbounded 表示不以起始视野为绘图边界
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
        Integer gridMm,
        String mode
    ) {
        /** 兼容旧版不含画布模式的布局。 */
        public Canvas(Integer widthMm, Integer heightMm, Integer gridMm) {
            this(widthMm, heightMm, gridMm, null);
        }
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
        Integer x,

        @NotNull(message = "矩形 y 坐标不能为空")
        Integer y,

        @NotNull(message = "矩形宽度不能为空")
        @Min(value = 1, message = "矩形宽度必须为正数")
        Integer w,

        @NotNull(message = "矩形高度不能为空")
        @Min(value = 1, message = "矩形高度必须为正数")
        Integer h
    ) {
    }

    /**
     * 参数化规划家具。中心点、长、深均使用整数毫米；SVG 线稿不作为数据来源。
     *
     * @param id 实例稳定 ID
     * @param templateId 内置图例模板 ID
     * @param roomClientId 归属手画空间 clientId
     * @param xMm 中心点 X
     * @param yMm 中心点 Y
     * @param widthMm 原始朝向长度
     * @param depthMm 原始朝向进深
     * @param rotationDeg 四分之一转角
     * @param wingWidthMm L/U 形图例的侧翼桌面宽
     * @param heightMm 柜体高度；旧实例缺省时使用图例默认高度
     */
    public record Furniture(
        @NotBlank @Size(max = 64) String id,
        @NotBlank @Size(max = 64) String templateId,
        @NotBlank @Size(max = 64) String roomClientId,
        @NotNull Integer xMm,
        @NotNull Integer yMm,
        @NotNull @Min(100) @Max(10000) Integer widthMm,
        @NotNull @Min(100) @Max(10000) Integer depthMm,
        @NotNull Integer rotationDeg,
        Integer wingWidthMm,
        @Min(100) @Max(3000) Integer heightMm
    ) {
        /** 兼容旧版仅保存占地与侧翼宽的实例构造。 */
        public Furniture(String id, String templateId, String roomClientId,
                         Integer xMm, Integer yMm, Integer widthMm, Integer depthMm,
                         Integer rotationDeg, Integer wingWidthMm) {
            this(id, templateId, roomClientId, xMm, yMm, widthMm, depthMm,
                rotationDeg, wingWidthMm, null);
        }
    }
}
