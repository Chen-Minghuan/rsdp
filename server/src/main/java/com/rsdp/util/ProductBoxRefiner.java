package com.rsdp.util;

import com.rsdp.dto.ProductBoundingBox;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AI 返回的产品 bbox 后处理器。
 *
 * <p>视觉模型输出的相对坐标存在噪声（越界、过小、重叠重复），
 * 本类在裁剪前做统一清洗：坐标钳制、面积/像素过滤、IoU 重叠去重。</p>
 */
@Slf4j
public final class ProductBoxRefiner {

    /**
     * 最小面积占比：bbox 面积不足页面 1% 视为噪声框，直接丢弃。
     */
    private static final double MIN_AREA_RATIO = 0.01;

    /**
     * 最小像素边长：换算到渲染页面上不足 32px 的框无意义。
     */
    private static final int MIN_PIXEL_EDGE = 32;

    /**
     * IoU 去重阈值：两个框 IoU 超过 0.8 视为同一产品，保留面积较大者。
     */
    private static final double IOU_DEDUP_THRESHOLD = 0.8;

    private ProductBoxRefiner() {
    }

    /**
     * 清洗结果：保留来源对象与清洗后的 bbox 配对。
     *
     * @param <T> 来源对象类型（如 AI 返回的完整产品记录）
     */
    public record Refined<T>(T source, ProductBoundingBox box) {
    }

    /**
     * 清洗 AI 返回的 bbox 列表。
     *
     * @param boxes      AI 原始 bbox 列表，可为空
     * @param pageWidth  渲染页面宽度（像素），用于像素级过滤
     * @param pageHeight 渲染页面高度（像素）
     * @return 清洗后的 bbox 列表（已按面积降序），不会为 null
     */
    public static List<ProductBoundingBox> refine(List<ProductBoundingBox> boxes, int pageWidth, int pageHeight) {
        return refineAll(boxes, java.util.function.Function.identity(), pageWidth, pageHeight)
            .stream().map(Refined::box).toList();
    }

    /**
     * 清洗 bbox 并保留来源对象配对（用于品类等附加信息的映射）。
     *
     * @param items      来源对象列表
     * @param extractor  从来源对象提取 bbox 的函数
     * @param pageWidth  渲染页面宽度（像素）
     * @param pageHeight 渲染页面高度（像素）
     * @param <T>        来源对象类型
     * @return 清洗结果列表（按面积降序），不会为 null
     */
    public static <T> List<Refined<T>> refineAll(List<T> items,
                                                 java.util.function.Function<T, ProductBoundingBox> extractor,
                                                 int pageWidth, int pageHeight) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<Refined<T>> candidates = new ArrayList<>(items.size());
        for (T item : items) {
            ProductBoundingBox clamped = clamp(extractor.apply(item));
            if (clamped == null) {
                continue;
            }
            double areaRatio = clamped.getWidth() * clamped.getHeight();
            if (areaRatio < MIN_AREA_RATIO) {
                log.debug("丢弃面积过小的 bbox，areaRatio={}", areaRatio);
                continue;
            }
            if (clamped.getWidth() * pageWidth < MIN_PIXEL_EDGE
                || clamped.getHeight() * pageHeight < MIN_PIXEL_EDGE) {
                log.debug("丢弃像素尺寸过小的 bbox，box={}", clamped);
                continue;
            }
            candidates.add(new Refined<>(item, clamped));
        }

        // 面积降序，IoU 去重保留大框
        candidates.sort(Comparator.comparingDouble(
            (Refined<T> r) -> r.box().getWidth() * r.box().getHeight()).reversed());

        List<Refined<T>> kept = new ArrayList<>(candidates.size());
        for (Refined<T> candidate : candidates) {
            boolean duplicated = false;
            for (Refined<T> existing : kept) {
                if (iou(candidate.box(), existing.box()) > IOU_DEDUP_THRESHOLD) {
                    duplicated = true;
                    break;
                }
            }
            if (!duplicated) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /**
     * 将 bbox 坐标钳制到 [0,1] 区间；钳制后宽高仍不合法时返回 null。
     */
    private static ProductBoundingBox clamp(ProductBoundingBox box) {
        if (box == null) {
            return null;
        }
        double x = Math.max(0.0, Math.min(1.0, box.getX()));
        double y = Math.max(0.0, Math.min(1.0, box.getY()));
        double width = Math.min(Math.max(0.0, box.getWidth()), 1.0 - x);
        double height = Math.min(Math.max(0.0, box.getHeight()), 1.0 - y);
        if (width <= 0.0 || height <= 0.0) {
            log.debug("丢弃钳制后无效的 bbox，原始 box={}", box);
            return null;
        }
        return new ProductBoundingBox(x, y, width, height);
    }

    /**
     * 计算两个 bbox 的交并比（IoU）。
     */
    private static double iou(ProductBoundingBox a, ProductBoundingBox b) {
        double interX = Math.max(a.getX(), b.getX());
        double interY = Math.max(a.getY(), b.getY());
        double interW = Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth()) - interX;
        double interH = Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight()) - interY;
        if (interW <= 0.0 || interH <= 0.0) {
            return 0.0;
        }
        double intersection = interW * interH;
        double union = a.getWidth() * a.getHeight() + b.getWidth() * b.getHeight() - intersection;
        return union <= 0.0 ? 0.0 : intersection / union;
    }
}
