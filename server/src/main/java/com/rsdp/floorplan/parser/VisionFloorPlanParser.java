package com.rsdp.floorplan.parser;

import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.service.VisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 视觉识别户型解析器（CAD 户型导入 P3）：包装既有 VisionService 两阶段链路
 * （初检 {@link VisionService#detectFloorPlanRooms} + 逐房间二次精修
 * {@link VisionService#refineFloorPlanRooms}），行为与重构前零变化。
 *
 * <p>支持图片与 PDF（PDF 上传时已渲染首页为 PNG）。</p>
 */
@Component
@RequiredArgsConstructor
public class VisionFloorPlanParser implements FloorPlanParser {

    private final VisionService visionService;

    /**
     * 户型图逐房间二次精修开关（二期，默认开）：初检完成后按房间 bbox 外扩裁剪
     * 单独精修 bbox/类型。管理端为异步链路，可承担逐房间串行调用的时延。
     * （自 AsyncTaskProcessor 迁入，配置键与语义不变。）
     */
    @Value("${rsdp.floor-plan.refine-enabled:true}")
    private boolean floorPlanRefineEnabled;

    @Override
    public boolean supports(FloorPlanFileType fileType) {
        return fileType == FloorPlanFileType.IMAGE || fileType == FloorPlanFileType.PDF;
    }

    @Override
    public FloorPlanParseResult parse(FloorPlanParseRequest request) {
        FloorPlanDetectResult detected = visionService.detectFloorPlanRooms(request.fileBytes(), request.hint());
        // 逐房间二次精修（二期）：精修后的 bbox 随 buildRooms 落库
        if (floorPlanRefineEnabled) {
            visionService.refineFloorPlanRooms(request.fileBytes(), detected);
        }
        return FloorPlanParseResult.vision(detected);
    }
}
