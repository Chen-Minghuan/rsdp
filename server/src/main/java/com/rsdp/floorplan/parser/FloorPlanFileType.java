package com.rsdp.floorplan.parser;

import java.util.Locale;

/**
 * 户型图文件类型（CAD 户型导入 P3）：决定 {@link FloorPlanParser} 路由。
 */
public enum FloorPlanFileType {

    /** 图片（jpg/png/webp 等，走视觉识别两阶段链路）。 */
    IMAGE,

    /** PDF（上传时已渲染首页为 PNG，识别链路同图片）。 */
    PDF,

    /** CAD 图纸（dwg/dxf，走 rsdp-cad-parser 矢量解析，跳过视觉识别/精修/标定）。 */
    CAD;

    /**
     * 按扩展名判定文件类型；dwg/dxf → CAD，pdf → PDF，其余 → IMAGE。
     *
     * @param extension 小写扩展名（不含点），可空
     * @return 文件类型
     */
    public static FloorPlanFileType fromExtension(String extension) {
        if (extension == null) {
            return IMAGE;
        }
        return switch (extension.trim().toLowerCase(Locale.ROOT)) {
            case "dwg", "dxf" -> CAD;
            case "pdf" -> PDF;
            default -> IMAGE;
        };
    }
}
