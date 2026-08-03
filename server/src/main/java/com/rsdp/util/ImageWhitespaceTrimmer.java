package com.rsdp.util;

import com.rsdp.dto.ProductBoundingBox;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * 产品图白边精修器。
 *
 * <p>AI 返回的 bbox 是粗估坐标，直接裁剪会带页边距、白边或切到文字。
 * 本类对裁剪结果做二次精修：背景色估计 + 四边空白扫描收紧 + 统一留白，
 * 让主图紧贴产品主体，显著提升准确度和观感。</p>
 *
 * <p>收紧策略分两种（{@link TrimOptions}）：</p>
 * <ul>
 *   <li>{@link TrimOptions#legacy()}：PDF 文档导入沿用至今的原始行为（白底产品图场景）；</li>
 *   <li>{@link TrimOptions#conservative()}：录入主图专用的保守模式——背景一致性门槛
 *   （场景背景不收紧）、每边收紧幅度上限、连续非背景段（细腿）保护，
 *   宁可多留边也绝不切到产品。</li>
 * </ul>
 */
@Slf4j
public final class ImageWhitespaceTrimmer {

    /**
     * 判定像素与背景色"接近"的每通道容差。
     */
    private static final int COLOR_TOLERANCE = 12;

    /**
     * 一行/列中背景色像素占比超过该值时视为空白行/列（legacy 默认）。
     */
    private static final double BLANK_RATIO = 0.995;

    /**
     * 背景色估计时取角落采样块的边长（像素）。
     */
    private static final int CORNER_SAMPLE = 5;

    /**
     * 保守模式下，行/列内连续非背景像素段达到该长度即视为内容（细腿保护）。
     */
    private static final int CONTENT_RUN_LENGTH = 3;

    /**
     * 收紧策略选项。
     *
     * @param blankRatio                  空白行/列判定阈值（背景色像素占比）
     * @param maxTrimRatio                每边最多收紧该边长度的比例（1.0 表示不限制）
     * @param protectContentRun           是否启用连续非背景段保护（细腿/细部件防切）
     * @param cornerConsistencyTolerance  四角背景色一致性容差（每通道），>0 时四角颜色
     *                                    差异超过该值判定为场景背景并跳过收紧；<=0 不启用
     */
    public record TrimOptions(double blankRatio, double maxTrimRatio,
                              boolean protectContentRun, int cornerConsistencyTolerance) {

        /** PDF 文档导入沿用的原始收紧行为。 */
        public static TrimOptions legacy() {
            return new TrimOptions(BLANK_RATIO, 1.0, false, 0);
        }

        /** 录入主图专用的保守收紧行为：宁可多留边也绝不切到产品。 */
        public static TrimOptions conservative() {
            return new TrimOptions(0.999, 0.25, true, 30);
        }
    }

    private ImageWhitespaceTrimmer() {
    }

    /**
     * 裁剪 + 白边精修 + 留白，输出 JPEG 字节（legacy 收紧行为，PDF 导入链路使用）。
     *
     * @param page         渲染页面图
     * @param bbox         AI 返回的相对坐标框（应已经过 {@link ProductBoxRefiner} 清洗）
     * @param expandRatio  裁剪前外扩比例（相对页面宽高），如 0.03
     * @param padRatio     收紧后留白比例（相对内容宽高），如 0.02
     * @param quality      JPEG 质量，0.0 ~ 1.0
     * @return 精修后的 JPEG 字节
     * @throws IOException 裁剪或编码失败
     */
    public static byte[] cropRefineToJpeg(BufferedImage page, ProductBoundingBox bbox,
                                          double expandRatio, double padRatio, float quality) throws IOException {
        return cropRefineToJpeg(page, bbox, expandRatio, padRatio, quality, TrimOptions.legacy());
    }

    /**
     * 裁剪 + 白边精修 + 留白，输出 JPEG 字节（可指定收紧策略）。
     *
     * <p>流程：bbox 按比例外扩（保证不缺边）→ 裁剪 → 角落估计背景色 →
     * 四边扫描空白行/列收紧到内容包围盒 → 按留白比例补齐背景色边距 → JPEG 编码。</p>
     *
     * @param page         原始图片
     * @param bbox         AI 返回的相对坐标框（应已经过 {@link ProductBoxRefiner} 清洗）
     * @param expandRatio  裁剪前外扩比例（相对图片宽高）
     * @param padRatio     收紧后留白比例（相对内容宽高）
     * @param quality      JPEG 质量，0.0 ~ 1.0
     * @param options      收紧策略
     * @return 精修后的 JPEG 字节
     * @throws IOException 裁剪或编码失败
     */
    public static byte[] cropRefineToJpeg(BufferedImage page, ProductBoundingBox bbox,
                                          double expandRatio, double padRatio, float quality,
                                          TrimOptions options) throws IOException {
        if (page == null) {
            throw new IllegalArgumentException("页面图不能为空");
        }
        if (bbox == null || !bbox.isValid()) {
            throw new IllegalArgumentException("裁剪框不合法");
        }
        if (options == null) {
            options = TrimOptions.legacy();
        }

        // 1. 外扩 bbox 并换算像素坐标（边界钳制）
        double expandedX = Math.max(0.0, bbox.getX() - expandRatio);
        double expandedY = Math.max(0.0, bbox.getY() - expandRatio);
        double expandedW = Math.min(1.0 - expandedX, bbox.getWidth() + 2 * expandRatio);
        double expandedH = Math.min(1.0 - expandedY, bbox.getHeight() + 2 * expandRatio);

        int x = (int) Math.round(expandedX * page.getWidth());
        int y = (int) Math.round(expandedY * page.getHeight());
        int width = Math.min((int) Math.round(expandedW * page.getWidth()), page.getWidth() - x);
        int height = Math.min((int) Math.round(expandedH * page.getHeight()), page.getHeight() - y);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("裁剪后尺寸无效");
        }

        // 2. 裁剪为独立的 RGB 图（避免共享 raster，统一色彩模型）
        BufferedImage cropped = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cropped.createGraphics();
        g.drawImage(page.getSubimage(x, y, width, height), 0, 0, null);
        g.dispose();

        // 3. 背景色估计（在裁剪图上、收紧前进行，确保拿到的是页边背景而非产品色）
        int[] cornerMeans = estimateCornerMeans(cropped);
        int bgRgb = meanOf(cornerMeans);

        // 4. 白边收紧
        BufferedImage trimmed = trimBlankEdges(cropped, bgRgb, cornerMeans, options);

        // 5. 留白 padding（使用页面背景色）
        BufferedImage padded = pad(trimmed, padRatio, bgRgb);

        return ImageCropper.encodeJpeg(padded, quality);
    }

    /**
     * 扫描四边空白行/列并收紧到内容包围盒；图像整体近乎纯色时原样返回。
     *
     * <p>保守模式下：四角背景色不一致（场景背景）时跳过收紧；
     * 每边收紧幅度不超过 {@code maxTrimRatio}；行/列内存在连续非背景段时
     * 不视为空白（细腿保护）。</p>
     */
    private static BufferedImage trimBlankEdges(BufferedImage image, int bgRgb, int[] cornerMeans,
                                                TrimOptions options) {
        int width = image.getWidth();
        int height = image.getHeight();

        // 背景一致性门槛：四角颜色差异大说明是场景/渐变背景，收紧不可靠，直接跳过
        if (options.cornerConsistencyTolerance() > 0 && !cornersConsistent(cornerMeans, options.cornerConsistencyTolerance())) {
            log.debug("四角背景色不一致，跳过白边收紧（场景背景）");
            return image;
        }

        int maxTrimX = (int) Math.floor(width * options.maxTrimRatio());
        int maxTrimY = (int) Math.floor(height * options.maxTrimRatio());

        int top = 0;
        while (top < height && top < maxTrimY && isBlankRow(image, top, 0, width, bgRgb, options)) {
            top++;
        }
        int bottom = height - 1;
        int bottomTrimmed = 0;
        while (bottom > top && bottomTrimmed < maxTrimY && isBlankRow(image, bottom, 0, width, bgRgb, options)) {
            bottom--;
            bottomTrimmed++;
        }
        int left = 0;
        while (left < width && left < maxTrimX && isBlankColumn(image, left, top, bottom, bgRgb, options)) {
            left++;
        }
        int right = width - 1;
        int rightTrimmed = 0;
        while (right > left && rightTrimmed < maxTrimX && isBlankColumn(image, right, top, bottom, bgRgb, options)) {
            right--;
            rightTrimmed++;
        }

        int contentW = right - left + 1;
        int contentH = bottom - top + 1;
        // 内容框无效或几乎没有可裁空间（纯色图）时保持原图
        if (contentW <= 0 || contentH <= 0 || (left == 0 && top == 0 && right == width - 1 && bottom == height - 1)) {
            return image;
        }
        return image.getSubimage(left, top, contentW, contentH);
    }

    /**
     * 按内容宽高比例补背景色边距。
     */
    private static BufferedImage pad(BufferedImage image, double padRatio, int bgRgb) {
        if (padRatio <= 0.0) {
            return image;
        }
        int padX = (int) Math.round(image.getWidth() * padRatio);
        int padY = (int) Math.round(image.getHeight() * padRatio);
        if (padX <= 0 && padY <= 0) {
            return image;
        }
        BufferedImage padded = new BufferedImage(
            image.getWidth() + 2 * padX, image.getHeight() + 2 * padY, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setColor(new java.awt.Color(bgRgb));
        g.fillRect(0, 0, padded.getWidth(), padded.getHeight());
        g.drawImage(image, padX, padY, null);
        g.dispose();
        return padded;
    }

    /**
     * 估计四个角落采样块各自的平均颜色（抗非白底页面）。
     *
     * @return 四角均值 RGB，顺序：左上、右上、左下、右下
     */
    private static int[] estimateCornerMeans(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int sample = Math.min(CORNER_SAMPLE, Math.min(width, height));
        int[][] corners = {{0, 0}, {width - sample, 0}, {0, height - sample}, {width - sample, height - sample}};
        int[] means = new int[4];
        for (int i = 0; i < corners.length; i++) {
            long r = 0;
            long g = 0;
            long b = 0;
            int count = 0;
            for (int dy = 0; dy < sample; dy++) {
                for (int dx = 0; dx < sample; dx++) {
                    int rgb = image.getRGB(corners[i][0] + dx, corners[i][1] + dy);
                    r += (rgb >> 16) & 0xFF;
                    g += (rgb >> 8) & 0xFF;
                    b += rgb & 0xFF;
                    count++;
                }
            }
            means[i] = ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
        }
        return means;
    }

    /** 四角均值的整体平均色（与原 estimateBackground 等价：各角采样数相同）。 */
    private static int meanOf(int[] cornerMeans) {
        long r = 0;
        long g = 0;
        long b = 0;
        for (int rgb : cornerMeans) {
            r += (rgb >> 16) & 0xFF;
            g += (rgb >> 8) & 0xFF;
            b += rgb & 0xFF;
        }
        int n = cornerMeans.length;
        return ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    /** 四角颜色两两差异均不超过容差时视为纯色背景。 */
    private static boolean cornersConsistent(int[] cornerMeans, int tolerance) {
        for (int i = 0; i < cornerMeans.length; i++) {
            for (int j = i + 1; j < cornerMeans.length; j++) {
                if (!channelsNear(cornerMeans[i], cornerMeans[j], tolerance)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean channelsNear(int rgb1, int rgb2, int tolerance) {
        return Math.abs(((rgb1 >> 16) & 0xFF) - ((rgb2 >> 16) & 0xFF)) <= tolerance
            && Math.abs(((rgb1 >> 8) & 0xFF) - ((rgb2 >> 8) & 0xFF)) <= tolerance
            && Math.abs((rgb1 & 0xFF) - (rgb2 & 0xFF)) <= tolerance;
    }

    private static boolean isBlankRow(BufferedImage image, int y, int fromX, int toX, int bgRgb,
                                      TrimOptions options) {
        int total = toX - fromX;
        int blank = 0;
        int nonBgRun = 0;
        int maxNonBgRun = 0;
        for (int x = fromX; x < toX; x++) {
            if (isNearBackground(image.getRGB(x, y), bgRgb)) {
                blank++;
                nonBgRun = 0;
            } else {
                nonBgRun++;
                maxNonBgRun = Math.max(maxNonBgRun, nonBgRun);
            }
        }
        if (options.protectContentRun() && maxNonBgRun >= CONTENT_RUN_LENGTH) {
            return false;
        }
        return (double) blank / total >= options.blankRatio();
    }

    private static boolean isBlankColumn(BufferedImage image, int x, int fromY, int toY, int bgRgb,
                                         TrimOptions options) {
        int total = toY - fromY + 1;
        int blank = 0;
        int nonBgRun = 0;
        int maxNonBgRun = 0;
        for (int y = fromY; y <= toY; y++) {
            if (isNearBackground(image.getRGB(x, y), bgRgb)) {
                blank++;
                nonBgRun = 0;
            } else {
                nonBgRun++;
                maxNonBgRun = Math.max(maxNonBgRun, nonBgRun);
            }
        }
        if (options.protectContentRun() && maxNonBgRun >= CONTENT_RUN_LENGTH) {
            return false;
        }
        return (double) blank / total >= options.blankRatio();
    }

    private static boolean isNearBackground(int rgb, int bgRgb) {
        return Math.abs(((rgb >> 16) & 0xFF) - ((bgRgb >> 16) & 0xFF)) <= COLOR_TOLERANCE
            && Math.abs(((rgb >> 8) & 0xFF) - ((bgRgb >> 8) & 0xFF)) <= COLOR_TOLERANCE
            && Math.abs((rgb & 0xFF) - (bgRgb & 0xFF)) <= COLOR_TOLERANCE;
    }
}
