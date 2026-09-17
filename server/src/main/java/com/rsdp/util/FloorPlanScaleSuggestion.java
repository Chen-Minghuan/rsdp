package com.rsdp.util;

import com.rsdp.dto.response.ScaleSuggestionResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 户型图自动标定建议（户型图优化二期）：用「高置信 OCR 尺寸 + bbox」反推全图比例。
 *
 * <p>对每个 dimensionText 可解析出宽深（{@link Dimensions#parseDimensionMm}）且有 bbox
 * 的房间，计算两个比例估计：mmPerPx_w = widthMm / (bbox.w × 图天然宽)、
 * mmPerPx_d = depthMm / (bbox.h × 图天然高)。所有估计值聚类：</p>
 * <ul>
 *   <li>存在 ≥2 个相互偏差 ≤5% 的簇 → status=auto，取最大一致簇的中位数为建议比例；
 *       不在主簇内的估计值视为离群（真实图纸家具图块常被拉伸，孤立标注不参与中位数计算），
 *       逐个放入 outliers；两簇规模相同时取簇中位数更接近全集中位数的簇；</li>
 *   <li>各估计互不一致（最大最小偏差 &gt;8%）但有 ≥2 个可解析房间 → status=candidates，
 *       返回各房间独立估计（宽深两估计取均值），并标记每个候选是否与任何其他候选一致
 *       （agreed，相对偏差 ≤5%）；</li>
 *   <li>其余 → status=null。</li>
 * </ul>
 *
 * <p>管理端 {@code FloorPlanService} 与官网 {@code PublicAiMatchService} 双端共用，
 * 任何一端不允许私写聚类规则副本。</p>
 */
public final class FloorPlanScaleSuggestion {

    /** 聚类偏差阈值：估计值两两相对偏差 ≤5% 视为同一簇。 */
    private static final double CLUSTER_TOLERANCE = 0.05;

    /** candidates 判定阈值：所有估计的最大最小相对偏差 >8% 才返回候选（5%~8% 之间维持 null）。 */
    private static final double CANDIDATES_DIVERGENCE = 0.08;

    private FloorPlanScaleSuggestion() {
        // 工具类禁止实例化
    }

    /**
     * 标定建议输入：单个房间的标注名、尺寸标注原文与归一化 bbox 尺寸。
     *
     * @param label         房间标注名（basisLabel/candidates 展示用），可空
     * @param dimensionText 尺寸标注原文（如 "4200×3800"），可空
     * @param w             bbox 归一化宽 [0,1]，可空
     * @param h             bbox 归一化高 [0,1]，可空
     */
    public record RoomExtent(String label, String dimensionText, Double w, Double h) {
    }

    /** 单个比例估计：来源房间（rooms 列表下标）+ 估计值。 */
    private record Estimate(int roomIndex, double mmPerPx) {
    }

    /**
     * 计算全图比例建议。
     *
     * @param rooms         房间列表（含 dimensionText 与归一化 bbox）
     * @param imageWidthPx  户型图天然宽（px），可空
     * @param imageHeightPx 户型图天然高（px），可空
     * @return 标定建议（status 为 auto/candidates/null，绝不返回 null 对象）
     */
    public static ScaleSuggestionResponse suggest(List<RoomExtent> rooms,
                                                  Integer imageWidthPx, Integer imageHeightPx) {
        ScaleSuggestionResponse response = new ScaleSuggestionResponse();
        if (rooms == null || rooms.isEmpty()
            || imageWidthPx == null || imageWidthPx <= 0
            || imageHeightPx == null || imageHeightPx <= 0) {
            return response;
        }

        List<Estimate> estimates = new ArrayList<>();
        List<PerRoom> parseableRooms = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            RoomExtent room = rooms.get(i);
            if (room == null || room.w() == null || room.h() == null
                || room.w() <= 0 || room.h() <= 0) {
                continue;
            }
            int[] dims = Dimensions.parseDimensionMm(room.dimensionText());
            if (dims == null) {
                continue;
            }
            double mmPerPxW = dims[0] / (room.w() * imageWidthPx);
            double mmPerPxD = dims[1] / (room.h() * imageHeightPx);
            if (mmPerPxW <= 0 || mmPerPxD <= 0) {
                continue;
            }
            estimates.add(new Estimate(i, mmPerPxW));
            estimates.add(new Estimate(i, mmPerPxD));
            parseableRooms.add(new PerRoom(i, room.label(), room.dimensionText(),
                (mmPerPxW + mmPerPxD) / 2.0));
        }
        if (estimates.isEmpty()) {
            return response;
        }

        // 聚类：排序后滑动窗口找最大「窗口内最大/最小相对偏差 ≤5%」的簇（≥2 个估计）
        List<Estimate> sorted = estimates.stream()
            .sorted(Comparator.comparingDouble(Estimate::mmPerPx))
            .toList();
        int[] clusterRange = largestClusterRange(sorted);
        if (clusterRange != null) {
            List<Estimate> cluster = sorted.subList(clusterRange[0], clusterRange[1] + 1);
            response.setStatus("auto");
            response.setMmPerPx(round2(median(cluster)));
            // basisLabel：簇内 rooms 列表中最靠前的房间
            int firstIndex = cluster.stream().mapToInt(Estimate::roomIndex).min().orElse(0);
            response.setBasisLabel(rooms.get(firstIndex).label());
            // 离群剔除：不在主簇内的估计值不参与中位数，逐个上报（升序）
            List<ScaleSuggestionResponse.Outlier> outliers = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                if (i >= clusterRange[0] && i <= clusterRange[1]) {
                    continue;
                }
                Estimate outlier = sorted.get(i);
                RoomExtent source = rooms.get(outlier.roomIndex());
                outliers.add(new ScaleSuggestionResponse.Outlier(
                    source.label(), round2(outlier.mmPerPx()), source.dimensionText()));
            }
            response.setOutliers(outliers);
            return response;
        }

        // candidates：各估计互不一致（最大最小相对偏差 >8%）且有 ≥2 个可解析房间
        double min = sorted.get(0).mmPerPx();
        double max = sorted.get(sorted.size() - 1).mmPerPx();
        if (parseableRooms.size() >= 2 && (max - min) / min > CANDIDATES_DIVERGENCE) {
            response.setStatus("candidates");
            response.setCandidates(parseableRooms.stream()
                .map(r -> new ScaleSuggestionResponse.Candidate(
                    r.label(), round2(r.avgMmPerPx()), r.dimensionText(),
                    agreedWithAny(r, parseableRooms)))
                .toList());
            return response;
        }
        return response;
    }

    /**
     * 找最大合法簇（排序数组下标区间，闭区间）：窗口内 (max-min)/min ≤ 5% 且大小 ≥2；
     * 规模并列时取簇中位数更接近全集中位数的簇（仍并列取先出现者），无合法簇返回 null。
     */
    private static int[] largestClusterRange(List<Estimate> sorted) {
        double overallMedian = median(sorted);
        int bestStart = -1;
        int bestEnd = -1;
        for (int start = 0; start < sorted.size(); start++) {
            for (int end = start + 1; end < sorted.size(); end++) {
                double lo = sorted.get(start).mmPerPx();
                double hi = sorted.get(end).mmPerPx();
                if ((hi - lo) / lo > CLUSTER_TOLERANCE) {
                    break;
                }
                int size = end - start + 1;
                int bestSize = bestEnd - bestStart + 1;
                if (bestStart < 0 || size > bestSize
                    || (size == bestSize && closerToOverallMedian(
                        sorted, start, end, bestStart, bestEnd, overallMedian))) {
                    bestStart = start;
                    bestEnd = end;
                }
            }
        }
        return bestStart < 0 ? null : new int[]{bestStart, bestEnd};
    }

    /** 并列簇取舍：新区间中位数比当前最优区间中位数更接近全集中位数时返回 true（等距保留先到者）。 */
    private static boolean closerToOverallMedian(List<Estimate> sorted, int start, int end,
                                                 int bestStart, int bestEnd, double overallMedian) {
        double newDistance = Math.abs(median(sorted.subList(start, end + 1)) - overallMedian);
        double bestDistance = Math.abs(median(sorted.subList(bestStart, bestEnd + 1)) - overallMedian);
        return newDistance < bestDistance;
    }

    /** 候选一致性：该房间独立估计与任何其他房间的独立估计相对偏差 ≤5% 即为一致。 */
    private static boolean agreedWithAny(PerRoom self, List<PerRoom> all) {
        for (PerRoom other : all) {
            if (other.roomIndex() == self.roomIndex()) {
                continue;
            }
            double lo = Math.min(self.avgMmPerPx(), other.avgMmPerPx());
            double hi = Math.max(self.avgMmPerPx(), other.avgMmPerPx());
            if ((hi - lo) / lo <= CLUSTER_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** 簇内中位数（偶数个取中间两值平均）。 */
    private static double median(List<Estimate> cluster) {
        int size = cluster.size();
        if (size % 2 == 1) {
            return cluster.get(size / 2).mmPerPx();
        }
        return (cluster.get(size / 2 - 1).mmPerPx() + cluster.get(size / 2).mmPerPx()) / 2.0;
    }

    /** 比例值保留两位小数（契约示例 3.42）。 */
    private static BigDecimal round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** 可解析房间的宽深两估计均值（candidates 分支用）。 */
    private record PerRoom(int roomIndex, String label, String dimensionText, double avgMmPerPx) {
    }
}
