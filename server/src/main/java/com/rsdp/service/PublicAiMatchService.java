package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.request.PublicAiMatchSchemeRequest;
import com.rsdp.dto.response.PublicAiMatchAnalyzeResponse;
import com.rsdp.dto.response.PublicAiMatchSchemeResponse;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.util.Dimensions;
import com.rsdp.util.FloorPlanScaleSuggestion;
import com.rsdp.util.ImageUploadValidator;
import com.rsdp.util.PdfRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 官网 AI 户型搭配公开服务（免登录）。
 *
 * <p>红线：响应绝不包含 RSKU 工厂报价字段（factoryCode/factoryName/factoryPrice），
 * 价格仅为参考售价（批次 2 起统一走 {@link PricingService#resolveSalePrice} 标准售价解析，
 * 由上游 {@link FloorPlanMatchingService} 方案项带出，不再直接使用 retail_price 列）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicAiMatchService {

    /** 户型图上传大小上限：10MB。 */
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;

    /** 预算缺省值（视为不限预算，满足 RoomSchemeRequest @NotNull）。 */
    private static final BigDecimal DEFAULT_BUDGET_LIMIT = new BigDecimal("999999");

    /** 空间类型枚举 → 中文名。 */
    private static final Map<String, String> ROOM_TYPE_NAMES = Map.of(
        "living_room", "客厅",
        "dining_room", "餐厅",
        "bedroom", "卧室",
        "kitchen", "厨房",
        "bathroom", "卫生间",
        "balcony", "阳台",
        "study", "书房",
        "hallway", "过道",
        "other", "其他"
    );

    private static final String CONFIDENCE_HIGH = "high";
    private static final String CONFIDENCE_LOW = "low";

    private final ImageUploadValidator imageUploadValidator;
    private final VisionService visionService;
    private final FloorPlanMatchingService floorPlanMatchingService;
    private final FloorPlanService floorPlanService;
    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;

    /** PDF 首页渲染 DPI（v3.0 §8 P2，默认 300，提升小字/尺寸标注清晰度）。 */
    @Value("${rsdp.floor-plan.pdf-render-dpi:300}")
    private float pdfRenderDpi;

    /**
     * 官网同步链路逐房间二次精修开关（二期，默认 false 保响应速度）：
     * 识别精度评分实测时临时开 true，测完恢复 false（官网同步链路不加时延）。
     */
    @Value("${rsdp.floor-plan.refine-public-enabled:false}")
    private boolean refinePublicEnabled;

    /**
     * 分析户型图：识别功能空间并解析尺寸标注。
     *
     * <p>支持 jpg/png 图片与 PDF（v3.0 §8 P2）：PDF 仅渲染第 1 页为 PNG
     * （{@link PdfRenderer#renderFirstPageAsPng}）后进入既有识别管线，落库存渲染后的
     * PNG，PDF 原文件不留存；PDF 非法/加密/空页返回 400 中文可读提示。</p>
     *
     * <p>识别完成后按 v3.0 §4.6 策略 B 落库（source=public，沉淀客户户型数据资产，
     * 响应追加 analysisId）。落库失败不阻断公开接口——识别结果已得出，落库仅作数据资产，
     * 异常记 warn 日志降级为不落库（analysisId=null）。</p>
     *
     * @param file 户型图（jpg/png 图片或 PDF，≤10MB）
     * @param hint 用户补充说明，可空
     * @return 空间识别结果列表 + analysisId（落库失败为 null）
     */
    public PublicAiMatchAnalyzeResponse analyze(MultipartFile file, String hint) {
        ImageUploadValidator.UploadKind uploadKind =
            imageUploadValidator.validateImageOrPdf(file, MAX_IMAGE_SIZE_BYTES);

        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取户型图上传文件失败", e);
            throw new BusinessException("读取上传图片失败");
        }
        // PDF：仅渲染首页为 PNG 进入识别管线，落库存渲染图（原件不留存）
        String storedFilename = file.getOriginalFilename();
        if (uploadKind == ImageUploadValidator.UploadKind.PDF) {
            imageBytes = PdfRenderer.renderFirstPageAsPng(imageBytes, pdfRenderDpi);
            storedFilename = "floor-plan-page1.png";
        }

        FloorPlanDetectResult detected = visionService.detectFloorPlanRooms(imageBytes, hint);
        // 逐房间二次精修（二期）：官网同步链路默认关闭保响应速度，评分实测时临时开
        if (refinePublicEnabled) {
            visionService.refineFloorPlanRooms(imageBytes, detected);
        }

        PublicAiMatchAnalyzeResponse response = new PublicAiMatchAnalyzeResponse();
        List<PublicAiMatchAnalyzeResponse.RoomItem> rooms = detected.getRooms().stream()
            .map(this::toRoomItem)
            .collect(Collectors.toList());
        response.setRooms(rooms);

        // 自动标定建议（二期）：OCR 尺寸 + bbox 反推全图比例（天然宽高经 ImageIO 读取）
        int[] pixelSize = readImagePixelSize(imageBytes);
        List<FloorPlanScaleSuggestion.RoomExtent> extents = detected.getRooms().stream()
            .map(room -> new FloorPlanScaleSuggestion.RoomExtent(
                StringUtils.hasText(room.getLabel()) ? room.getLabel()
                    : ROOM_TYPE_NAMES.get(room.getRoomType()),
                room.getDimensionText(), room.getW(), room.getH()))
            .collect(Collectors.toList());
        response.setScaleSuggestion(FloorPlanScaleSuggestion.suggest(
            extents, pixelSize != null ? pixelSize[0] : null, pixelSize != null ? pixelSize[1] : null));

        try {
            response.setAnalysisId(floorPlanService.savePublicAnalysis(
                imageBytes, storedFilename, detected));
        } catch (Exception e) {
            log.warn("官网户型分析落库失败，降级为不落库（识别结果照常返回）", e);
        }
        return response;
    }

    /**
     * 生成公开安全的 AI 搭配方案。
     *
     * <p>统一走 {@link FloorPlanMatchingService#matchRoomScheme}（双端唯一出口）：
     * widthMm/depthMm 提供时尺寸硬规则 R1~R5 生效（修复此前组装内部请求时丢弃尺寸的
     * 现网缺陷），缺失时退化为原 AI 选品行为。再将方案项回查 RSPU 展示字段与主图后
     * 脱敏输出（价格取方案项参考售价 salePrice，即 PricingService 标准售价解析口径，
     * 绝不含工厂字段）。</p>
     *
     * @param request 搭配请求（风格/预算/尺寸均可空）
     * @return 公开搭配方案（仅参考售价，无工厂字段）
     */
    public PublicAiMatchSchemeResponse generateScheme(PublicAiMatchSchemeRequest request) {
        RoomSchemeResponse scheme = floorPlanMatchingService.matchRoomScheme(
            request.getWidthMm(),
            request.getDepthMm(),
            request.getStylePreference(),
            request.getBudgetLimit() != null ? request.getBudgetLimit() : DEFAULT_BUDGET_LIMIT
        );

        List<SchemeItemResponse> schemeItems = scheme.getItems() != null
            ? scheme.getItems() : List.of();
        List<String> rspuIds = schemeItems.stream()
            .map(SchemeItemResponse::getRspuId)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();

        Map<String, RspuMaster> rspuMap = batchRspuMap(rspuIds);
        Map<String, String> imageUrlMap = batchPrimaryImageUrls(rspuIds);

        List<PublicAiMatchSchemeResponse.Item> items = new java.util.ArrayList<>();
        BigDecimal totalRetailPrice = BigDecimal.ZERO;
        for (SchemeItemResponse schemeItem : schemeItems) {
            RspuMaster rspu = rspuMap.get(schemeItem.getRspuId());
            if (rspu == null) {
                continue;
            }
            PublicAiMatchSchemeResponse.Item item = new PublicAiMatchSchemeResponse.Item();
            item.setRspuId(rspu.getRspuId());
            item.setProductName(rspu.getProductName());
            item.setCategoryPath(rspu.getCategoryPath());
            item.setPositioningLabel(rspu.getPositioningLabel());
            // 参考售价：统一取方案项 salePrice（PricingService 标准售价解析口径，
            // 消除 retail_price 与标准售价双口径漂移），未定价为 null
            item.setRetailPrice(schemeItem.getSalePrice());
            item.setPrimaryImageUrl(imageUrlMap.get(rspu.getRspuId()));
            items.add(item);
            if (schemeItem.getSalePrice() != null) {
                totalRetailPrice = totalRetailPrice.add(schemeItem.getSalePrice());
            }
        }

        PublicAiMatchSchemeResponse response = new PublicAiMatchSchemeResponse();
        response.setReasoning(scheme.getReasoning());
        response.setTotalRetailPrice(totalRetailPrice);
        response.setItems(items);
        return response;
    }

    private PublicAiMatchAnalyzeResponse.RoomItem toRoomItem(FloorPlanDetectResult.Room room) {
        PublicAiMatchAnalyzeResponse.RoomItem item = new PublicAiMatchAnalyzeResponse.RoomItem();
        item.setRoomType(room.getRoomType());
        String roomName = ROOM_TYPE_NAMES.get(room.getRoomType());
        if (!StringUtils.hasText(roomName)) {
            roomName = StringUtils.hasText(room.getLabel()) ? room.getLabel() : "其他";
        }
        item.setRoomName(roomName);
        item.setDimensionText(room.getDimensionText());

        int[] dims = Dimensions.parseDimensionMm(room.getDimensionText());
        if (dims != null) {
            item.setWidthMm(dims[0]);
            item.setDepthMm(dims[1]);
            item.setAreaM2(BigDecimal.valueOf((long) dims[0] * dims[1])
                .divide(BigDecimal.valueOf(1_000_000L), 2, RoundingMode.HALF_UP));
            item.setConfidence(CONFIDENCE_HIGH);
        } else {
            item.setConfidence(CONFIDENCE_LOW);
        }
        // 空间位置框（相对原图归一化坐标）：前端叠加展示与识别精度评测使用，无敏感信息
        item.setX(room.getX());
        item.setY(room.getY());
        item.setW(room.getW());
        item.setH(room.getH());
        return item;
    }

    /**
     * 读取图片像素宽/高（自动标定建议的换算参照）；读取失败返回 null（建议降级为 status=null）。
     *
     * @param imageBytes 图片字节
     * @return [widthPx, heightPx]，失败返回 null
     */
    private int[] readImagePixelSize(byte[] imageBytes) {
        try {
            java.awt.image.BufferedImage image =
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new int[] {image.getWidth(), image.getHeight()};
            }
        } catch (Exception e) {
            log.warn("读取户型图像素尺寸失败，标定建议按无像素尺寸处理", e);
        }
        return null;
    }

    private Map<String, RspuMaster> batchRspuMap(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectBatchIds(rspuIds).stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
            .in("rspu_id", rspuIds)
            .eq("is_primary", true)
            .orderByDesc("created_at"));
        Map<String, String> result = new HashMap<>();
        for (ImageAssets image : images) {
            // 按创建时间倒序遍历，putIfAbsent 保留每个产品最新一张
            result.putIfAbsent(image.getRspuId(), "/api/v1/images/" + image.getImageId());
        }
        return result;
    }
}
