package com.rsdp.service;

import com.rsdp.entity.FloorPlanFurniture;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.mapper.FloorPlanFurnitureMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * 家具视觉语义回填（P1-C，Scene-aware Block Fallback 后续）：
 *
 * <p>对 Parser 输出的 VALID 且类别为 UNKNOWN/OTHER 的家具候选，按 visionGroupId 分组
 * （同块定义+同尺寸+同几何指纹），每组只调一次视觉模型（isolation 线稿 + context 环境裁剪），
 * 结果回填组内全部实例。职责边界：视觉只回答"这是什么"（category/semanticLabel/confidence），
 * 尺寸/位置等事实永远来自 CAD 几何。</p>
 *
 * <p>置信度分级：≥0.85 高可信（categoryConfidence=high），0.60~0.85 待人工复核（mid），
 * &lt;0.60 不采信（保留原类别，仅记录 vision_confidence）。视觉结果 confirmation_status 仍为 AUTO，
 * 人工确认走既有 PUT /furniture 流（cad_analysis_feedback 沉淀预测→人工 Diff）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FloorPlanFurnitureSemanticService {

    /** 视觉回填的语义来源标记（semantic_source 列）。 */
    public static final String SEMANTIC_SOURCE_VISION = "vision_qwen3vl";

    /** 高可信阈值（直接采纳）。 */
    static final double CONFIDENCE_ACCEPT = 0.60;
    /** 高置信阈值（categoryConfidence=high）。 */
    static final double CONFIDENCE_HIGH = 0.85;

    private final FloorPlanFurnitureMapper furnitureMapper;
    private final VisionService visionService;
    private final com.rsdp.mapper.FloorPlanRoomMapper roomMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.rsdp.service.storage.StorageService storageService;

    @Value("${rsdp.floor-plan.furniture-vision-enabled:true}")
    private boolean enabled;

    @Value("${rsdp.floor-plan.furniture-vision-max-groups:30}")
    private int maxGroups;

    @Value("${rsdp.floor-plan.furniture-vision-context-margin-mm:600}")
    private double contextMarginMm;

    /**
     * 对一次 CAD 解析产出的家具候选做视觉语义回填。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 目标 Revision
     * @param cadResult        CAD 解析结果（需带 isolationImages；缺省时仅用 context 裁剪）
     * @param previewBytes     规范预览 PNG 字节（context 裁剪底图），可空
     */
    public void enrichCadFurniture(String analysisId, Integer analysisRevision,
                                   CadParseResult cadResult, byte[] previewBytes) {
        if (!enabled || cadResult == null) {
            return;
        }
        persistIsolationImages(analysisId, cadResult);
        List<FloorPlanFurniture> candidates = furnitureMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FloorPlanFurniture>()
                .eq(FloorPlanFurniture::getAnalysisId, analysisId)
                .eq(FloorPlanFurniture::getAnalysisRevision, analysisRevision)
                .eq(FloorPlanFurniture::getCandidateStatus, "VALID")
                .isNotNull(FloorPlanFurniture::getVisionGroupId)
                .in(FloorPlanFurniture::getCategory, List.of("UNKNOWN", "OTHER"))
                .eq(FloorPlanFurniture::getConfirmationStatus, FloorPlanFurniture.CONFIRM_AUTO));
        candidates = scopedCandidates(analysisId, analysisRevision, candidates);
        if (candidates.isEmpty()) {
            return;
        }

        // 按视觉分组聚合；组内按占地面积降序，优先识别大件（沙发/床比五金符号价值高）
        Map<String, List<FloorPlanFurniture>> groups = new LinkedHashMap<>();
        for (FloorPlanFurniture f : candidates) {
            groups.computeIfAbsent(f.getVisionGroupId(), k -> new ArrayList<>()).add(f);
        }
        List<Map.Entry<String, List<FloorPlanFurniture>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.comparingDouble(
            (Map.Entry<String, List<FloorPlanFurniture>> e) -> -groupArea(e.getValue())));

        int done = 0, filled = 0;
        for (Map.Entry<String, List<FloorPlanFurniture>> entry : ordered) {
            if (done >= maxGroups) {
                break;
            }
            done++;
            String visionGroupId = entry.getKey();
            List<FloorPlanFurniture> members = entry.getValue();
            FloorPlanFurniture rep = members.get(0);
            try {
                byte[] isolation = isolationImage(cadResult, visionGroupId);
                if (isolation == null) {
                    continue; // 无线稿图（该组解析时未输出），跳过
                }
                byte[] context = cropContext(previewBytes, cadResult, rep);
                VisionService.FurnitureVisionVerdict verdict =
                    visionService.classifyFurnitureObject(isolation, context);
                if (verdict == null) {
                    continue;
                }
                for (FloorPlanFurniture member : members) {
                    boolean adopted = applyVerdict(member, verdict);
                    member.setUpdatedAt(java.time.LocalDateTime.now());
                    furnitureMapper.updateById(member);
                    if (adopted) {
                        filled++;
                    }
                }
            } catch (Exception e) {
                // 单组失败不影响其他组与主流程
                log.warn("家具视觉语义分组处理失败，analysisId={}，vg={}", analysisId, visionGroupId, e);
            }
        }
        log.info("家具视觉语义回填完成，analysisId={}，revision={}，候选组={}，识别={}组，采纳回填={}件",
            analysisId, analysisRevision, groups.size(), done, filled);
    }

    /**
     * 应用视觉判定到单件家具：≥0.60 采纳（category/label/语义来源），<0.60 仅记置信度。
     *
     * @return 是否采纳了类别
     */
    private boolean applyVerdict(FloorPlanFurniture member, VisionService.FurnitureVisionVerdict verdict) {
        Double confidence = verdict.confidence();
        member.setVisionConfidence(confidence != null ? BigDecimal.valueOf(confidence) : null);
        if (confidence == null || confidence < CONFIDENCE_ACCEPT
            || "UNKNOWN".equals(verdict.category())) {
            return false;
        }
        member.setCategory(verdict.category());
        if (StringUtils.hasText(verdict.semanticLabel())) {
            member.setSemanticLabel(verdict.semanticLabel());
        }
        member.setSemanticSource(SEMANTIC_SOURCE_VISION);
        member.setCategoryConfidence(confidence >= CONFIDENCE_HIGH ? "high" : "mid");
        return true;
    }

    private static double groupArea(List<FloorPlanFurniture> members) {
        FloorPlanFurniture f = members.get(0);
        double w = f.getGeometryWidthMm() != null ? f.getGeometryWidthMm() : 0;
        double d = f.getGeometryDepthMm() != null ? f.getGeometryDepthMm() : 0;
        return w * d;
    }

    /**
     * isolation 视图对象键（确定性路径，免建索引表）。
     *
     * @param analysisId    分析批次 ID
     * @param visionGroupId 视觉语义分组 ID
     * @return 存储对象键
     */
    static String isolationObjectKey(String analysisId, String visionGroupId) {
        return "floor-plan-isolation/" + analysisId + "/" + visionGroupId + ".png";
    }

    /**
     * 把 Parser 输出的 isolation 视图持久化到对象存储（确定性键、覆盖写）：
     * 视觉识别从"只能在解析当下内存中运行"变为"任意时刻可按需重跑"（官网游客按需触发依赖此）。
     *
     * @param analysisId 分析批次 ID
     * @param cadResult  CAD 解析结果（含 isolationImages）
     */
    public void persistIsolationImages(String analysisId, CadParseResult cadResult) {
        Map<String, String> images = cadResult != null ? cadResult.getIsolationImages() : null;
        if (images == null || images.isEmpty()) {
            return;
        }
        int stored = 0;
        for (Map.Entry<String, String> entry : images.entrySet()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(entry.getValue());
                storageService.store(new ByteArrayInputStream(bytes),
                    isolationObjectKey(analysisId, entry.getKey()), bytes.length, "image/png");
                stored++;
            } catch (Exception e) {
                log.warn("isolation 视图存储失败，analysisId={}，vg={}", analysisId, entry.getKey(), e);
            }
        }
        log.info("isolation 视图已存储，analysisId={}，数量={}/{}", analysisId, stored, images.size());
    }

    /** 按需回填结果摘要。 */
    public record EnrichSummary(int candidateGroups, int processedGroups, int adopted, int skippedNoImage) {
    }

    /**
     * 按需视觉语义回填（官网游客"AI 识别家具名称"按钮 / 管理端重跑）：
     * 从对象存储读取 isolation 视图（解析时已持久化），仅 isolation 单图识别（无 context），
     * 只处理仍为 UNKNOWN/OTHER 且 AUTO 状态的 VALID 候选，幂等可重入。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 目标 Revision
     * @return 处理摘要
     */
    public EnrichSummary enrichFromStorage(String analysisId, Integer analysisRevision) {
        if (!enabled) {
            return new EnrichSummary(0, 0, 0, 0);
        }
        List<FloorPlanFurniture> candidates = furnitureMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FloorPlanFurniture>()
                .eq(FloorPlanFurniture::getAnalysisId, analysisId)
                .eq(FloorPlanFurniture::getAnalysisRevision, analysisRevision)
                .eq(FloorPlanFurniture::getCandidateStatus, "VALID")
                .isNotNull(FloorPlanFurniture::getVisionGroupId)
                .in(FloorPlanFurniture::getCategory, List.of("UNKNOWN", "OTHER"))
                .eq(FloorPlanFurniture::getConfirmationStatus, FloorPlanFurniture.CONFIRM_AUTO));
        candidates = scopedCandidates(analysisId, analysisRevision, candidates);
        if (candidates.isEmpty()) {
            return new EnrichSummary(0, 0, 0, 0);
        }
        Map<String, List<FloorPlanFurniture>> groups = new LinkedHashMap<>();
        for (FloorPlanFurniture f : candidates) {
            groups.computeIfAbsent(f.getVisionGroupId(), k -> new ArrayList<>()).add(f);
        }
        List<Map.Entry<String, List<FloorPlanFurniture>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.comparingDouble(
            (Map.Entry<String, List<FloorPlanFurniture>> e) -> -groupArea(e.getValue())));

        int processed = 0, adopted = 0, skippedNoImage = 0;
        for (Map.Entry<String, List<FloorPlanFurniture>> entry : ordered) {
            if (processed >= maxGroups) {
                break;
            }
            String visionGroupId = entry.getKey();
            byte[] isolation = readIsolationImage(analysisId, visionGroupId);
            if (isolation == null) {
                skippedNoImage++;
                continue;
            }
            processed++;
            try {
                VisionService.FurnitureVisionVerdict verdict =
                    visionService.classifyFurnitureObject(isolation, null);
                if (verdict == null) {
                    continue;
                }
                for (FloorPlanFurniture member : entry.getValue()) {
                    if (applyVerdict(member, verdict)) {
                        adopted++;
                    }
                    member.setUpdatedAt(java.time.LocalDateTime.now());
                    furnitureMapper.updateById(member);
                }
            } catch (Exception e) {
                log.warn("按需视觉语义分组处理失败，analysisId={}，vg={}", analysisId, visionGroupId, e);
            }
        }
        log.info("按需家具视觉语义回填完成，analysisId={}，revision={}，候选组={}，识别={}，缺图跳过={}，采纳={}",
            analysisId, analysisRevision, groups.size(), processed, skippedNoImage, adopted);
        return new EnrichSummary(groups.size(), processed, adopted, skippedNoImage);
    }

    private List<FloorPlanFurniture> scopedCandidates(String analysisId, Integer revision,
                                                      List<FloorPlanFurniture> candidates) {
        var rooms = roomMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.rsdp.entity.FloorPlanRoom>()
                .eq(com.rsdp.entity.FloorPlanRoom::getAnalysisId, analysisId)
                .eq(com.rsdp.entity.FloorPlanRoom::getAnalysisRevision, revision));
        var roomIds = rooms.stream()
            .filter(r -> r.getRoomType() != null && java.util.Set.of(
                "LIVING", "LIVING_ROOM", "DINING", "DINING_ROOM", "KITCHEN")
                .contains(r.getRoomType().toUpperCase(java.util.Locale.ROOT)))
            .map(com.rsdp.entity.FloorPlanRoom::getRoomId).collect(java.util.stream.Collectors.toSet());
        return candidates.stream().filter(f -> f.getRoomId() != null && roomIds.contains(f.getRoomId()))
            .filter(f -> !"DUPLICATE".equals(f.getDuplicateStatus())).toList();
    }

    private byte[] readIsolationImage(String analysisId, String visionGroupId) {
        try (InputStream stream = storageService.get(isolationObjectKey(analysisId, visionGroupId))) {
            return stream.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] isolationImage(CadParseResult cadResult, String visionGroupId) {
        Map<String, String> images = cadResult.getIsolationImages();
        if (images == null) {
            return null;
        }
        String base64 = images.get(visionGroupId);
        return StringUtils.hasText(base64) ? Base64.getDecoder().decode(base64) : null;
    }

    /**
     * context 裁剪：家具 bbox 外扩 contextMarginMm，从规范预览 PNG 按 drawingBounds 毫米→像素
     * 映射裁出局部环境。任何失败返回 null（isolation 单图也能识别）。
     */
    private byte[] cropContext(byte[] previewBytes, CadParseResult cadResult, FloorPlanFurniture rep) {
        try {
            if (previewBytes == null || previewBytes.length == 0
                || cadResult.getDrawingBounds() == null || rep.getFootprintPolygon() == null) {
                return null;
            }
            BufferedImage preview = ImageIO.read(new ByteArrayInputStream(previewBytes));
            if (preview == null) {
                return null;
            }
            // 毫米→像素映射必须用预览图自身的渲染范围（多候选时 preview.bounds 是
            // 候选并集，≠ drawingBounds），否则裁剪会偏出图外退化成 1px 细条被模型拒收
            CadParseResult.Bounds bounds = cadResult.getPreview() != null
                && cadResult.getPreview().getBounds() != null
                ? cadResult.getPreview().getBounds() : cadResult.getDrawingBounds();
            List<List<Double>> polygon = objectMapper.readValue(rep.getFootprintPolygon(),
                objectMapper.getTypeFactory().constructCollectionType(List.class,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)));
            if (polygon == null || polygon.isEmpty()) {
                return null;
            }
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (List<Double> p : polygon) {
                minX = Math.min(minX, p.get(0));
                minY = Math.min(minY, p.get(1));
                maxX = Math.max(maxX, p.get(0));
                maxY = Math.max(maxY, p.get(1));
            }
            minX -= contextMarginMm;
            minY -= contextMarginMm;
            maxX += contextMarginMm;
            maxY += contextMarginMm;

            double spanX = bounds.getMaxX() - bounds.getMinX();
            double spanY = bounds.getMaxY() - bounds.getMinY();
            if (spanX <= 0 || spanY <= 0) {
                return null;
            }
            int x0 = clamp((int) Math.floor((minX - bounds.getMinX()) / spanX * preview.getWidth()), 0, preview.getWidth() - 1);
            int x1 = clamp((int) Math.ceil((maxX - bounds.getMinX()) / spanX * preview.getWidth()), x0 + 1, preview.getWidth());
            // 预览 Y 轴翻转（毫米 Y 向上，像素 Y 向下）
            int y0 = clamp((int) Math.floor((bounds.getMaxY() - maxY) / spanY * preview.getHeight()), 0, preview.getHeight() - 1);
            int y1 = clamp((int) Math.ceil((bounds.getMaxY() - minY) / spanY * preview.getHeight()), y0 + 1, preview.getHeight());

            if (x1 - x0 < 32 || y1 - y0 < 32) {
                return null; // 退化裁剪（家具在预览范围外等）：退回 isolation 单图
            }
            BufferedImage crop = preview.getSubimage(x0, y0, x1 - x0, y1 - y0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(crop, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("context 裁剪失败（退回 isolation 单图）", e);
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
