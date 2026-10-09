package com.rsdp.floorplan.parser.dto;

import com.rsdp.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * CAD 解析结果（Scene 契约）服务端校验（设计文档 V1.2.3 §18.6）：
 * 新 Java DTO 必须显式接收并校验新字段——对象 ID 非空、坐标有限值、
 * footprintPolygon 闭合、尺寸非负、枚举合法；不得依赖
 * {@code @JsonIgnoreProperties} 静默吞掉非法结果。
 *
 * <p>校验失败一律抛 {@link BusinessException}（中文可读提示），
 * 由异步任务体系落 failed。</p>
 */
public final class CadParseResultValidator {

    private static final Set<String> PARSE_STATUSES = Set.of("SUCCESS", "PARTIAL", "NEEDS_REVIEW", "FAILED");
    private static final Set<String> FURNITURE_CATEGORIES =
        Set.of("SOFA", "BED", "TABLE", "CHAIR", "CABINET", "OTHER", "UNKNOWN");
    private static final Set<String> SOURCE_KINDS = Set.of("INSERT", "EXPLODED");

    private CadParseResultValidator() {
    }

    /**
     * 校验 CAD 解析结果 Scene 契约字段（在 success=true 路径调用）。
     *
     * @param result CAD 解析服务输出（preview.pngBase64 可能尚未清空）
     * @throws BusinessException 契约违例（对象 ID 为空/坐标非有限值/轮廓未闭合/尺寸为负/枚举越界）
     */
    public static void validate(CadParseResult result) {
        // success=false ⟺ FAILED：两者矛盾时以 FAILED 为准（不信任自相矛盾的成功）
        if (StringUtils.hasText(result.getParseStatus())) {
            if (!PARSE_STATUSES.contains(result.getParseStatus())) {
                throw new BusinessException("CAD 图纸解析失败: parseStatus 非法: " + result.getParseStatus());
            }
            if ("FAILED".equals(result.getParseStatus())) {
                throw new BusinessException("CAD 图纸解析失败: "
                    + (StringUtils.hasText(result.getErrorMessage()) ? result.getErrorMessage() : "parseStatus=FAILED"));
            }
            if (!result.isSuccess()) {
                throw new BusinessException("CAD 图纸解析失败: "
                    + (StringUtils.hasText(result.getErrorMessage()) ? result.getErrorMessage() : "success=false"));
            }
        }

        List<CadParseResult.Room> rooms = result.getRooms();
        if (rooms != null) {
            for (int i = 0; i < rooms.size(); i++) {
                CadParseResult.Room room = rooms.get(i);
                validatePolygon(room.getPolygon(), false, "rooms[" + i + "].polygon");
                validatePoint(room.getLabelPoint(), "rooms[" + i + "].labelPoint");
                validateBounds(room.getBBox(), "rooms[" + i + "].bBox");
            }
        }

        List<CadParseResult.TargetSceneCandidate> candidates = result.getTargetSceneCandidates();
        if (candidates != null) {
            for (int i = 0; i < candidates.size(); i++) {
                CadParseResult.TargetSceneCandidate candidate = candidates.get(i);
                if (!StringUtils.hasText(candidate.getTargetId())) {
                    throw new BusinessException("CAD 图纸解析失败: targetSceneCandidates[" + i + "].targetId 不能为空");
                }
                validateBounds(candidate.getBounds(), "targetSceneCandidates[" + i + "].bounds");
            }
        }
        if (result.getTargetScene() != null) {
            validateBounds(result.getTargetScene().getBounds(), "targetScene.bounds");
        }
        validateBounds(result.getDrawingBounds(), "drawingBounds");
        if (result.getUnitResolution() != null && result.getUnitResolution().getScaleToMm() != null) {
            validateFinite(result.getUnitResolution().getScaleToMm(), "unitResolution.scaleToMm");
            if (result.getUnitResolution().getScaleToMm() <= 0) {
                throw new BusinessException("CAD 图纸解析失败: unitResolution.scaleToMm 必须为正数");
            }
        }

        List<CadParseResult.Furniture> furnitureList = result.getFurniture();
        if (furnitureList != null) {
            for (int i = 0; i < furnitureList.size(); i++) {
                validateFurniture(furnitureList.get(i), i);
            }
        }
    }

    /** 单件家具契约校验：ID 非空、枚举合法、坐标有限、轮廓闭合、尺寸非负。 */
    private static void validateFurniture(CadParseResult.Furniture furniture, int index) {
        String prefix = "furniture[" + index + "]";
        if (!StringUtils.hasText(furniture.getFurnitureId())) {
            throw new BusinessException("CAD 图纸解析失败: " + prefix + ".furnitureId 不能为空");
        }
        if (StringUtils.hasText(furniture.getCategory())
            && !FURNITURE_CATEGORIES.contains(furniture.getCategory())) {
            throw new BusinessException("CAD 图纸解析失败: " + prefix + ".category 非法: " + furniture.getCategory());
        }
        if (StringUtils.hasText(furniture.getSourceKind())
            && !SOURCE_KINDS.contains(furniture.getSourceKind())) {
            throw new BusinessException("CAD 图纸解析失败: " + prefix + ".sourceKind 非法: " + furniture.getSourceKind());
        }
        validatePoint(furniture.getPosition(), prefix + ".position");
        validateFinite(furniture.getRotationDeg(), prefix + ".rotationDeg");
        validatePolygon(furniture.getFootprintPolygon(), true, prefix + ".footprintPolygon");
        validateNonNegative(furniture.getGeometryWidthMm(), prefix + ".geometryWidthMm");
        validateNonNegative(furniture.getGeometryDepthMm(), prefix + ".geometryDepthMm");
        validateNonNegative(furniture.getAnnotatedWidthMm(), prefix + ".annotatedWidthMm");
        validateNonNegative(furniture.getAnnotatedDepthMm(), prefix + ".annotatedDepthMm");
        validateNonNegative(furniture.getEffectiveWidthMm(), prefix + ".effectiveWidthMm");
        validateNonNegative(furniture.getEffectiveDepthMm(), prefix + ".effectiveDepthMm");
        validateNonNegative(furniture.getHeightMm(), prefix + ".heightMm");
    }

    /** 多边形坐标有限性 + 闭合校验（requireClosed=true 时首尾点必须一致）。 */
    private static void validatePolygon(List<List<Double>> polygon, boolean requireClosed, String field) {
        if (polygon == null) {
            return;
        }
        for (List<Double> point : polygon) {
            if (point == null || point.size() < 2) {
                throw new BusinessException("CAD 图纸解析失败: " + field + " 顶点坐标不完整");
            }
            validateFinite(point.get(0), field);
            validateFinite(point.get(1), field);
        }
        if (requireClosed && polygon.size() >= 3) {
            List<Double> first = polygon.get(0);
            List<Double> last = polygon.get(polygon.size() - 1);
            if (!first.get(0).equals(last.get(0)) || !first.get(1).equals(last.get(1))) {
                throw new BusinessException("CAD 图纸解析失败: " + field + " 必须为闭合多边形（首尾顶点一致）");
            }
        }
    }

    private static void validatePoint(CadParseResult.Point point, String field) {
        if (point == null) {
            return;
        }
        validateFinite(point.getX(), field + ".x");
        validateFinite(point.getY(), field + ".y");
    }

    private static void validateBounds(CadParseResult.Bounds bounds, String field) {
        if (bounds == null) {
            return;
        }
        validateFinite(bounds.getMinX(), field + ".minX");
        validateFinite(bounds.getMinY(), field + ".minY");
        validateFinite(bounds.getMaxX(), field + ".maxX");
        validateFinite(bounds.getMaxY(), field + ".maxY");
    }

    private static void validateFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new BusinessException("CAD 图纸解析失败: " + field + " 必须为有限数值");
        }
    }

    private static void validateNonNegative(Double value, String field) {
        if (value != null) {
            validateFinite(value, field);
            if (value < 0) {
                throw new BusinessException("CAD 图纸解析失败: " + field + " 不能为负数");
            }
        }
    }
}
