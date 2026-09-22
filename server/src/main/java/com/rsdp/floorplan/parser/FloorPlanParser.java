package com.rsdp.floorplan.parser;

/**
 * 户型图解析器抽象（CAD 户型导入 P3，docs/08-roadmap/CAD户型导入架构设计.md §4.1）。
 *
 * <p>实现：{@link VisionFloorPlanParser}（图片/PDF，包装既有 VisionService 两阶段链路，
 * 行为零变化）与 {@link CadFloorPlanParser}（dwg/dxf，调 rsdp-cad-parser 微服务）。</p>
 */
public interface FloorPlanParser {

    /**
     * 是否支持该文件类型。
     *
     * @param fileType 文件类型
     * @return true = 本解析器处理
     */
    boolean supports(FloorPlanFileType fileType);

    /**
     * 解析户型文件。
     *
     * @param request 解析请求
     * @return 解析结果（视觉或 CAD 通道）
     */
    FloorPlanParseResult parse(FloorPlanParseRequest request);
}
