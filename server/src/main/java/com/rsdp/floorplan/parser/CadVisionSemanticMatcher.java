package com.rsdp.floorplan.parser;

import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.util.Dimensions;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CAD 几何与阅览图视觉语义融合器。
 *
 * <p>DWG 常见“墙体和尺寸在模型空间、房间名仅存在于出图图片”的情况。本类只把视觉识别出的
 * 名称和类型写入 CAD 空间，polygon、面积、宽深和标签锚点始终保留 CAD 原值。匹配前分别按
 * 两组空间中心的包络做归一化，因此不依赖阅览图白边、标题栏或像素尺寸与 CAD 预览一致。</p>
 */
public final class CadVisionSemanticMatcher {

    private static final double MAX_SPATIAL_DISTANCE = 0.38;
    private static final double MAX_MATCH_SCORE = 0.46;

    private CadVisionSemanticMatcher() {
    }

    /**
     * 将视觉空间语义融合到 CAD 解析结果中。
     *
     * <p>已有可靠 CAD 名称不会被覆盖；命中的 unnamedRegion 会转成普通 room，未命中区域继续
     * 保持“未命名”供人工确认。方法会原地更新 {@code cadResult} 并返回融合统计。</p>
     *
     * @param cadResult    CAD 精确几何结果
     * @param visionResult 阅览图视觉识别结果
     * @return 语义融合统计
     */
    public static FusionSummary fuse(CadParseResult cadResult, FloorPlanDetectResult visionResult) {
        return fuse(cadResult, visionResult, null);
    }

    /**
     * 将视觉语义融合到 CAD 结果，并利用参考图尺寸校正整图白边/标题栏造成的坐标偏移。
     *
     * @param cadResult          CAD 精确几何结果
     * @param visionResult       阅览图视觉识别结果
     * @param referenceImageBytes 原始阅览图；为空时回退到中心包络归一化
     * @return 语义融合统计
     */
    public static FusionSummary fuse(CadParseResult cadResult, FloorPlanDetectResult visionResult,
                                     byte[] referenceImageBytes) {
        if (cadResult == null || visionResult == null || visionResult.getRooms() == null) {
            return new FusionSummary(0, 0, 0);
        }

        List<CadTarget> cadTargets = collectCadTargets(cadResult);
        List<VisionTarget> visionTargets = collectVisionTargets(visionResult);
        if (cadTargets.isEmpty() || visionTargets.isEmpty()) {
            return new FusionSummary(0, cadTargets.size(), visionTargets.size());
        }

        Bounds cadBounds = drawingBounds(cadResult, cadTargets);
        boolean registered = mapUsingDrawingCrop(
            cadResult, visionTargets, cadBounds, referenceImageBytes);
        if (!registered) {
            Bounds visionBounds = normalizeVisionCenters(visionTargets);
            mapVisionToCadCoordinates(visionTargets, cadBounds, visionBounds);
        }
        normalizeMappedTargets(cadTargets, visionTargets, cadBounds);
        List<MatchedGroup> matches = matchByContainmentThenDistance(cadTargets, visionTargets);

        applyMatches(cadResult, matches);
        updateQualityIssues(cadResult, matches.size(), cadTargets.size());
        return new FusionSummary(matches.size(), cadTargets.size(), visionTargets.size());
    }

    private static Bounds drawingBounds(CadParseResult cadResult, List<CadTarget> cadTargets) {
        CadParseResult.Bounds bounds = cadResult.getDrawingBounds();
        if (bounds != null && bounds.getMinX() != null && bounds.getMinY() != null
            && bounds.getMaxX() != null && bounds.getMaxY() != null
            && bounds.getMaxX() > bounds.getMinX() && bounds.getMaxY() > bounds.getMinY()) {
            return new Bounds(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY());
        }
        return boundsOf(cadTargets.stream().map(CadTarget::center).toList());
    }

    private static boolean mapUsingDrawingCrop(CadParseResult cadResult,
                                                List<VisionTarget> visionTargets,
                                                Bounds cadBounds,
                                                byte[] referenceImageBytes) {
        if (referenceImageBytes == null || referenceImageBytes.length == 0
            || cadResult.getDrawingBounds() == null) {
            return false;
        }
        try {
            var image = ImageIO.read(new ByteArrayInputStream(referenceImageBytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return false;
            }
            double minX = visionTargets.stream().map(VisionTarget::room)
                .mapToDouble(FloorPlanDetectResult.Room::getX).min().orElse(0);
            double minY = visionTargets.stream().map(VisionTarget::room)
                .mapToDouble(FloorPlanDetectResult.Room::getY).min().orElse(0);
            double maxX = visionTargets.stream().map(VisionTarget::room)
                .mapToDouble(room -> room.getX() + room.getW()).max().orElse(1);
            double maxY = visionTargets.stream().map(VisionTarget::room)
                .mapToDouble(room -> room.getY() + room.getH()).max().orElse(1);
            double centerX = (minX + maxX) / 2.0;
            double centerY = (minY + maxY) / 2.0;
            double cropWidth = Math.max(0.01, (maxX - minX) * 1.02);
            double cropHeight = Math.max(0.01, (maxY - minY) * 1.02);
            double cadAspect = (cadBounds.maxX() - cadBounds.minX())
                / (cadBounds.maxY() - cadBounds.minY());
            double normalizedImageAspect = cadAspect * image.getHeight() / image.getWidth();
            if (cropWidth / cropHeight > normalizedImageAspect) {
                cropHeight = cropWidth / normalizedImageAspect;
            } else {
                cropWidth = cropHeight * normalizedImageAspect;
            }
            Bounds crop = new Bounds(centerX - cropWidth / 2.0, centerY - cropHeight / 2.0,
                centerX + cropWidth / 2.0, centerY + cropHeight / 2.0);
            double cadWidth = cadBounds.maxX() - cadBounds.minX();
            double cadHeight = cadBounds.maxY() - cadBounds.minY();
            for (VisionTarget target : visionTargets) {
                double nx = normalize(target.center().x(), crop.minX(), crop.maxX());
                double ny = normalize(target.center().y(), crop.minY(), crop.maxY());
                target.cadPoint = new Point(
                    cadBounds.minX() + nx * cadWidth,
                    cadBounds.maxY() - ny * cadHeight);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void normalizeMappedTargets(List<CadTarget> cadTargets,
                                               List<VisionTarget> visionTargets,
                                               Bounds bounds) {
        double width = bounds.maxX() - bounds.minX();
        double height = bounds.maxY() - bounds.minY();
        for (CadTarget target : cadTargets) {
            target.normalized = new Point(
                normalize(target.center().x(), bounds.minX(), bounds.maxX()),
                normalize(bounds.maxY() - target.center().y(), 0, height));
        }
        for (VisionTarget target : visionTargets) {
            target.normalized = new Point(
                normalize(target.cadPoint.x(), bounds.minX(), bounds.maxX()),
                normalize(bounds.maxY() - target.cadPoint.y(), 0, height));
        }
    }

    private static List<CadTarget> collectCadTargets(CadParseResult cadResult) {
        List<CadTarget> result = new ArrayList<>();
        List<CadParseResult.Room> rooms = cadResult.getRooms() != null
            ? cadResult.getRooms() : new ArrayList<>();
        for (CadParseResult.Room room : rooms) {
            if (!hasReliableSemantic(room.getLabel(), room.getRoomType())) {
                Point center = cadCenter(room.getLabelPoint(), room.getBBox(), room.getPolygon());
                if (center != null) {
                    result.add(CadTarget.forRoom(room, center, room.getAreaM2()));
                }
            }
        }
        List<CadParseResult.UnnamedRegion> unnamed = cadResult.getUnnamedRegions() != null
            ? cadResult.getUnnamedRegions() : new ArrayList<>();
        for (CadParseResult.UnnamedRegion region : unnamed) {
            Point center = cadCenter(region.getLabelPoint(), region.getBBox(), region.getPolygon());
            if (center != null) {
                result.add(CadTarget.forUnnamed(region, center, region.getAreaM2()));
            }
        }
        return result;
    }

    private static List<VisionTarget> collectVisionTargets(FloorPlanDetectResult visionResult) {
        List<VisionTarget> result = new ArrayList<>();
        for (FloorPlanDetectResult.Room room : visionResult.getRooms()) {
            if (!hasUsableVisionSemantic(room) || !validBox(room)) {
                continue;
            }
            double centerX = room.getX() + room.getW() / 2.0;
            double centerY = room.getY() + room.getH() / 2.0;
            double bboxArea = room.getW() * room.getH();
            int[] dims = Dimensions.parseDimensionMm(room.getDimensionText());
            Double dimensionArea = dims != null ? dims[0] * dims[1] / 1_000_000.0 : null;
            result.add(new VisionTarget(room, new Point(centerX, centerY), bboxArea, dimensionArea));
        }
        return result;
    }

    private static Bounds normalizeVisionCenters(List<VisionTarget> targets) {
        Bounds bounds = boundsOf(targets.stream().map(VisionTarget::center).toList());
        for (VisionTarget target : targets) {
            target.normalized = new Point(
                normalize(target.center().x(), bounds.minX(), bounds.maxX()),
                normalize(target.center().y(), bounds.minY(), bounds.maxY()));
        }
        return bounds;
    }

    private static void mapVisionToCadCoordinates(List<VisionTarget> targets,
                                                   Bounds cadBounds, Bounds visionBounds) {
        double cadWidth = cadBounds.maxX() - cadBounds.minX();
        double cadHeight = cadBounds.maxY() - cadBounds.minY();
        for (VisionTarget target : targets) {
            double nx = normalize(target.center().x(), visionBounds.minX(), visionBounds.maxX());
            double ny = normalize(target.center().y(), visionBounds.minY(), visionBounds.maxY());
            target.cadPoint = new Point(
                cadBounds.minX() + nx * cadWidth,
                cadBounds.maxY() - ny * cadHeight);
        }
    }

    private static List<MatchedGroup> matchByContainmentThenDistance(List<CadTarget> cadTargets,
                                                                      List<VisionTarget> visionTargets) {
        Map<Integer, List<VisionTarget>> grouped = new LinkedHashMap<>();
        Set<Integer> usedVision = new HashSet<>();
        Set<Integer> dominantCad = dominantTargets(cadTargets);
        Set<Integer> usedCad = new HashSet<>();

        // 优先按“视觉中心落入 CAD polygon”匹配。开放式主体空间允许聚合多个视觉语义；
        // 普通空间的包含关系先汇总成候选，再按中心距离做全局一对一，避免重叠 polygon
        // 里先出现的“客厅”抢走后续位置更吻合的“卧室”。
        List<Candidate> containmentCandidates = new ArrayList<>();
        for (int visionIndex = 0; visionIndex < visionTargets.size(); visionIndex++) {
            VisionTarget vision = visionTargets.get(visionIndex);
            int dominantHit = -1;
            double dominantArea = Double.MAX_VALUE;
            for (int cadIndex = 0; cadIndex < cadTargets.size(); cadIndex++) {
                CadTarget cad = cadTargets.get(cadIndex);
                if (!contains(cad.polygon(), vision.cadPoint)) {
                    continue;
                }
                if (dominantCad.contains(cadIndex)) {
                    double area = positive(cad.areaM2())
                        ? cad.areaM2() : polygonEnvelopeArea(cad.polygon());
                    if (area < dominantArea) {
                        dominantArea = area;
                        dominantHit = cadIndex;
                    }
                } else {
                    containmentCandidates.add(new Candidate(
                        cadIndex, visionIndex, distance(cad.normalized, vision.normalized)));
                }
            }
            if (dominantHit >= 0) {
                grouped.computeIfAbsent(dominantHit, ignored -> new ArrayList<>()).add(vision);
                usedVision.add(visionIndex);
                usedCad.add(dominantHit);
            }
        }
        containmentCandidates.sort(Comparator.comparingDouble(Candidate::score));
        for (Candidate candidate : containmentCandidates) {
            if (usedCad.contains(candidate.cadIndex()) || usedVision.contains(candidate.visionIndex())) {
                continue;
            }
            grouped.put(candidate.cadIndex(), new ArrayList<>(List.of(
                visionTargets.get(candidate.visionIndex()))));
            usedCad.add(candidate.cadIndex());
            usedVision.add(candidate.visionIndex());
        }

        // 少数中心落在门洞/边界外时，按布局距离兜底；只补尚无语义的 CAD 空间，防止重复错配。
        for (Candidate candidate : buildCandidates(cadTargets, visionTargets)) {
            if (candidate.score() > MAX_MATCH_SCORE
                || usedCad.contains(candidate.cadIndex())
                || usedVision.contains(candidate.visionIndex())) {
                continue;
            }
            grouped.put(candidate.cadIndex(), new ArrayList<>(List.of(
                visionTargets.get(candidate.visionIndex()))));
            usedCad.add(candidate.cadIndex());
            usedVision.add(candidate.visionIndex());
        }

        List<MatchedGroup> matches = new ArrayList<>();
        grouped.forEach((cadIndex, semantics) ->
            matches.add(new MatchedGroup(cadTargets.get(cadIndex), semantics)));
        return matches;
    }

    private static Set<Integer> dominantTargets(List<CadTarget> cadTargets) {
        List<Double> areas = cadTargets.stream().map(CadTarget::areaM2)
            .filter(CadVisionSemanticMatcher::positive).sorted().toList();
        if (areas.isEmpty()) {
            return Set.of();
        }
        double median = areas.get(areas.size() / 2);
        // 只把明显的开放式主体空间视为可聚合区域；普通大卧室（20~25㎡）仍须一对一。
        double threshold = Math.max(25.0, median * 4.0);
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < cadTargets.size(); i++) {
            if (positive(cadTargets.get(i).areaM2())
                && cadTargets.get(i).areaM2() >= threshold) {
                result.add(i);
            }
        }
        return result;
    }

    private static List<Candidate> buildCandidates(List<CadTarget> cadTargets,
                                                    List<VisionTarget> visionTargets) {
        double totalCadArea = cadTargets.stream().map(CadTarget::areaM2)
            .filter(CadVisionSemanticMatcher::positive).mapToDouble(Double::doubleValue).sum();
        double totalVisionBoxArea = visionTargets.stream().mapToDouble(VisionTarget::bboxArea).sum();
        List<Candidate> result = new ArrayList<>();
        for (int i = 0; i < cadTargets.size(); i++) {
            CadTarget cad = cadTargets.get(i);
            for (int j = 0; j < visionTargets.size(); j++) {
                VisionTarget vision = visionTargets.get(j);
                double spatial = distance(cad.normalized, vision.normalized);
                if (spatial > MAX_SPATIAL_DISTANCE) {
                    continue;
                }
                double score = spatial * 0.82;
                if (positive(cad.areaM2()) && positive(vision.dimensionArea())) {
                    score += ratioPenalty(cad.areaM2(), vision.dimensionArea()) * 0.14;
                } else if (positive(cad.areaM2()) && totalCadArea > 0 && totalVisionBoxArea > 0) {
                    double cadShare = cad.areaM2() / totalCadArea;
                    double visionShare = vision.bboxArea() / totalVisionBoxArea;
                    score += ratioPenalty(cadShare, visionShare) * 0.08;
                }
                result.add(new Candidate(i, j, score));
            }
        }
        result.sort(Comparator.comparingDouble(Candidate::score));
        return result;
    }

    private static void applyMatches(CadParseResult cadResult, List<MatchedGroup> matches) {
        List<CadParseResult.Room> rooms = cadResult.getRooms() != null
            ? new ArrayList<>(cadResult.getRooms()) : new ArrayList<>();
        List<CadParseResult.UnnamedRegion> unnamed = cadResult.getUnnamedRegions() != null
            ? new ArrayList<>(cadResult.getUnnamedRegions()) : new ArrayList<>();

        for (MatchedGroup match : matches) {
            List<FloorPlanDetectResult.Room> semantics = match.visions().stream()
                .map(VisionTarget::room).toList();
            String mergedLabel = mergeLabels(semantics);
            String primaryType = primaryRoomType(semantics);
            CadTarget target = match.cad();
            if (target.room != null) {
                if (!StringUtils.hasText(target.room.getLabel()) || isGenericLabel(target.room.getLabel())) {
                    target.room.setLabel(mergedLabel);
                }
                if (!StringUtils.hasText(target.room.getRoomType())
                    || "OTHER".equalsIgnoreCase(target.room.getRoomType())) {
                    target.room.setRoomType(primaryType);
                }
                if (!StringUtils.hasText(target.room.getConfidence())
                    || "low".equalsIgnoreCase(target.room.getConfidence())) {
                    target.room.setConfidence("mid");
                }
                continue;
            }

            CadParseResult.UnnamedRegion region = target.unnamed;
            CadParseResult.Room fused = new CadParseResult.Room();
            fused.setLabel(mergedLabel);
            fused.setRoomType(primaryType);
            fused.setPolygon(region.getPolygon());
            fused.setBBox(region.getBBox());
            fused.setWidthMm(region.getWidthMm());
            fused.setDepthMm(region.getDepthMm());
            fused.setAreaM2(region.getAreaM2());
            fused.setLabelPoint(region.getLabelPoint());
            fused.setDimensionSource("cad_geometry");
            fused.setConfidence("mid");
            String dimensionText = semantics.stream().map(FloorPlanDetectResult.Room::getDimensionText)
                .filter(StringUtils::hasText).findFirst().orElse(null);
            if (dimensionText != null && semantics.size() == 1) {
                CadParseResult.DimensionCheck check = new CadParseResult.DimensionCheck();
                check.setAnnotated(dimensionText);
                check.setConsistent(null);
                fused.setDimensionCheck(check);
            }
            rooms.add(fused);
            unnamed.remove(region);
        }
        cadResult.setRooms(rooms);
        cadResult.setUnnamedRegions(unnamed);
    }

    private static String mergeLabels(List<FloorPlanDetectResult.Room> semantics) {
        List<String> labels = semantics.stream()
            .sorted(Comparator.comparingInt(room -> roomTypePriority(room.getRoomType())))
            .map(CadVisionSemanticMatcher::semanticLabel)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        return labels.isEmpty() ? "未命名空间" : String.join("、", labels);
    }

    private static String semanticLabel(FloorPlanDetectResult.Room room) {
        if (StringUtils.hasText(room.getLabel()) && !isGenericLabel(room.getLabel())) {
            return room.getLabel().trim();
        }
        return switch (room.getRoomType() == null ? "" : room.getRoomType().toLowerCase()) {
            case "living_room" -> "客厅";
            case "dining_room" -> "餐厅";
            case "bedroom" -> "卧室";
            case "kitchen" -> "厨房";
            case "bathroom" -> "卫生间";
            case "balcony" -> "阳台";
            case "study", "study_room" -> "书房";
            case "hallway" -> "过道";
            default -> "其他空间";
        };
    }

    private static String primaryRoomType(List<FloorPlanDetectResult.Room> semantics) {
        return semantics.stream()
            .map(FloorPlanDetectResult.Room::getRoomType)
            .filter(StringUtils::hasText)
            .min(Comparator.comparingInt(CadVisionSemanticMatcher::roomTypePriority))
            .orElse("other");
    }

    private static int roomTypePriority(String roomType) {
        return switch (roomType == null ? "" : roomType.toLowerCase()) {
            case "living_room" -> 0;
            case "bedroom" -> 1;
            case "kitchen" -> 2;
            case "bathroom" -> 3;
            case "study", "study_room" -> 4;
            case "dining_room" -> 5;
            case "balcony" -> 6;
            case "hallway" -> 7;
            default -> 8;
        };
    }

    private static void updateQualityIssues(CadParseResult cadResult, int matched, int totalCadTargets) {
        if (matched <= 0) {
            return;
        }
        List<CadParseResult.QualityIssue> issues = cadResult.getQualityIssues() != null
            ? new ArrayList<>(cadResult.getQualityIssues()) : new ArrayList<>();
        issues.removeIf(issue -> "NO_ROOM_LABELS".equals(issue.getCode()));
        if (matched < totalCadTargets) {
            CadParseResult.QualityIssue issue = new CadParseResult.QualityIssue();
            issue.setLevel("warn");
            issue.setCode("SEMANTIC_FUSION_PARTIAL");
            issue.setMessage("已从阅览图补全 " + matched + "/" + totalCadTargets
                + " 个空间名称，其余空间请人工确认");
            issues.add(issue);
        }
        cadResult.setQualityIssues(issues);
    }

    private static boolean hasReliableSemantic(String label, String roomType) {
        return StringUtils.hasText(label) && !isGenericLabel(label)
            && StringUtils.hasText(roomType) && !"OTHER".equalsIgnoreCase(roomType);
    }

    private static boolean hasUsableVisionSemantic(FloorPlanDetectResult.Room room) {
        return room != null && ((StringUtils.hasText(room.getLabel()) && !isGenericLabel(room.getLabel()))
            || (StringUtils.hasText(room.getRoomType()) && !"other".equalsIgnoreCase(room.getRoomType())));
    }

    private static boolean isGenericLabel(String label) {
        if (!StringUtils.hasText(label)) {
            return true;
        }
        String value = label.trim();
        return value.matches("^(未命名)?(空间|房间|区域)\\s*\\d*$")
            || "未知".equals(value) || "OTHER".equalsIgnoreCase(value);
    }

    private static boolean validBox(FloorPlanDetectResult.Room room) {
        return room.getX() != null && room.getY() != null && room.getW() != null && room.getH() != null
            && room.getW() > 0 && room.getH() > 0;
    }

    private static Point cadCenter(CadParseResult.Point labelPoint, CadParseResult.Bounds bbox,
                                   List<List<Double>> polygon) {
        if (labelPoint != null && labelPoint.getX() != null && labelPoint.getY() != null) {
            return new Point(labelPoint.getX(), labelPoint.getY());
        }
        if (bbox != null && bbox.getMinX() != null && bbox.getMaxX() != null
            && bbox.getMinY() != null && bbox.getMaxY() != null) {
            return new Point((bbox.getMinX() + bbox.getMaxX()) / 2.0,
                (bbox.getMinY() + bbox.getMaxY()) / 2.0);
        }
        if (polygon == null || polygon.isEmpty()) {
            return null;
        }
        double x = 0;
        double y = 0;
        int count = 0;
        for (List<Double> point : polygon) {
            if (point != null && point.size() >= 2 && point.get(0) != null && point.get(1) != null) {
                x += point.get(0);
                y += point.get(1);
                count++;
            }
        }
        return count > 0 ? new Point(x / count, y / count) : null;
    }

    private static Bounds boundsOf(List<Point> points) {
        double minX = points.stream().mapToDouble(Point::x).min().orElse(0);
        double minY = points.stream().mapToDouble(Point::y).min().orElse(0);
        double maxX = points.stream().mapToDouble(Point::x).max().orElse(minX + 1);
        double maxY = points.stream().mapToDouble(Point::y).max().orElse(minY + 1);
        return new Bounds(minX, minY, maxX, maxY);
    }

    private static double normalize(double value, double min, double max) {
        double range = max - min;
        return Math.abs(range) < 1e-9 ? 0.5 : (value - min) / range;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static double ratioPenalty(double a, double b) {
        return Math.min(1.0, Math.abs(Math.log(Math.max(a, 1e-9) / Math.max(b, 1e-9))));
    }

    private static boolean contains(List<List<Double>> polygon, Point point) {
        if (polygon == null || polygon.size() < 3 || point == null) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            List<Double> a = polygon.get(i);
            List<Double> b = polygon.get(j);
            if (a == null || b == null || a.size() < 2 || b.size() < 2) {
                continue;
            }
            double ax = a.get(0);
            double ay = a.get(1);
            double bx = b.get(0);
            double by = b.get(1);
            boolean crosses = (ay > point.y()) != (by > point.y())
                && point.x() < (bx - ax) * (point.y() - ay) / (by - ay) + ax;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double polygonEnvelopeArea(List<List<Double>> polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (List<Double> point : polygon) {
            if (point != null && point.size() >= 2) {
                minX = Math.min(minX, point.get(0));
                minY = Math.min(minY, point.get(1));
                maxX = Math.max(maxX, point.get(0));
                maxY = Math.max(maxY, point.get(1));
            }
        }
        return (maxX - minX) * (maxY - minY);
    }

    private static boolean positive(Double value) {
        return value != null && value > 0 && Double.isFinite(value);
    }

    /** 语义融合统计。 */
    public record FusionSummary(int matchedCount, int cadCandidateCount, int visionCandidateCount) {
    }

    private record Point(double x, double y) {
    }

    private record Bounds(double minX, double minY, double maxX, double maxY) {
    }

    private record Candidate(int cadIndex, int visionIndex, double score) {
    }

    private record MatchedGroup(CadTarget cad, List<VisionTarget> visions) {
    }

    private static final class CadTarget {
        private final CadParseResult.Room room;
        private final CadParseResult.UnnamedRegion unnamed;
        private final Point center;
        private final Double areaM2;
        private Point normalized;

        private CadTarget(CadParseResult.Room room, CadParseResult.UnnamedRegion unnamed,
                          Point center, Double areaM2) {
            this.room = room;
            this.unnamed = unnamed;
            this.center = center;
            this.areaM2 = areaM2;
        }

        static CadTarget forRoom(CadParseResult.Room room, Point center, Double areaM2) {
            return new CadTarget(room, null, center, areaM2);
        }

        static CadTarget forUnnamed(CadParseResult.UnnamedRegion unnamed, Point center, Double areaM2) {
            return new CadTarget(null, unnamed, center, areaM2);
        }

        Point center() {
            return center;
        }

        Double areaM2() {
            return areaM2;
        }

        List<List<Double>> polygon() {
            return room != null ? room.getPolygon() : unnamed.getPolygon();
        }
    }

    private static final class VisionTarget {
        private final FloorPlanDetectResult.Room room;
        private final Point center;
        private final double bboxArea;
        private final Double dimensionArea;
        private Point normalized;
        private Point cadPoint;

        private VisionTarget(FloorPlanDetectResult.Room room, Point center,
                             double bboxArea, Double dimensionArea) {
            this.room = room;
            this.center = center;
            this.bboxArea = bboxArea;
            this.dimensionArea = dimensionArea;
        }

        FloorPlanDetectResult.Room room() {
            return room;
        }

        Point center() {
            return center;
        }

        double bboxArea() {
            return bboxArea;
        }

        Double dimensionArea() {
            return dimensionArea;
        }
    }
}
