package com.rsdp.dto.response;

/**
 * 手画布局毫米坐标矩形。
 *
 * @param id 组成块 ID；主布置区可空
 * @param x 左上角 X
 * @param y 左上角 Y
 * @param w 宽度
 * @param h 高度
 */
public record ManualFloorPlanRectResponse(String id, Integer x, Integer y, Integer w, Integer h) {
}
