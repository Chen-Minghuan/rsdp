package com.rsdp.floorplan.parser;

import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.floorplan.parser.dto.CadParseResult;

/**
 * 户型图解析结果（CAD 户型导入 P3）：视觉识别与 CAD 解析两通道的统一出口。
 *
 * <p>视觉通道携带 {@link FloorPlanDetectResult}（走既有尺寸三级提取 {@code buildRooms}）；
 * CAD 通道携带 {@link CadParseResult}（走 {@code buildCadRooms} 直接落库，尺寸来自真实几何）。</p>
 */
public record FloorPlanParseResult(FloorPlanFileType fileType,
                                   FloorPlanDetectResult visionResult,
                                   CadParseResult cadResult,
                                   byte[] previewBytes) {

    /**
     * 构造视觉识别通道结果。
     *
     * @param detected AI 空间识别结果
     * @return 解析结果
     */
    public static FloorPlanParseResult vision(FloorPlanDetectResult detected) {
        return new FloorPlanParseResult(FloorPlanFileType.IMAGE, detected, null, null);
    }

    /**
     * 构造 CAD 解析通道结果。
     *
     * @param cadResult CAD 解析服务输出
     * @return 解析结果
     */
    public static FloorPlanParseResult cad(CadParseResult cadResult, byte[] previewBytes) {
        return new FloorPlanParseResult(FloorPlanFileType.CAD, null, cadResult, previewBytes);
    }

    /**
     * 是否 CAD 解析通道结果。
     *
     * @return true = CAD 通道
     */
    public boolean isCad() {
        return cadResult != null;
    }
}
