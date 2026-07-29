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
 */
@Slf4j
public final class ImageWhitespaceTrimmer {

    /**
     * 判定像素与背景色"接近"的每通道容差。
     */
    private static final int COLOR_TOLERANCE = 12;

    /**
     * 一行/列中背景色像素占比超过该值时视为空白行/列。
     */
    private static final double BLANK_RATIO = 0.995;

    /**
     * 背景色估计时取角落采样块的边长（像素）。
     */
    private static final int CORNER_SAMPLE = 5;

    private ImageWhitespaceTrimmer() {
    }

    /**
     * 裁剪 + 白边精修 + 留白，输出 JPEG 字节。
     *
     * <p>流程：bbox 按比例外扩（保证不缺边）→ 裁剪 → 角落估计背景色 →
     * 四边扫描空白行/列收紧到内容包围盒 → 按留白比例补齐背景色边距 → JPEG 编码。</p>
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
        if (page == null) {
            throw new IllegalArgumentException("页面图不能为空");
        }
        if (bbox == null || !bbox.isValid()) {
            throw new IllegalArgumentException("裁剪框不合法");
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
        int bgRgb = estimateBackground(cropped);

        // 4. 白边收紧
        BufferedImage trimmed = trimBlankEdges(cropped, bgRgb);

        // 5. 留白 padding（使用页面背景色）
        BufferedImage padded = pad(trimmed, padRatio, bgRgb);

        return ImageCropper.encodeJpeg(padded, quality);
    }

    /**
     * 扫描四边空白行/列并收紧到内容包围盒；图像整体近乎纯色时原样返回。
     */
    private static BufferedImage trimBlankEdges(BufferedImage image, int bgRgb) {
        int width = image.getWidth();
        int height = image.getHeight();

        int top = 0;
        while (top < height && isBlankRow(image, top, 0, width, bgRgb)) {
            top++;
        }
        int bottom = height - 1;
        while (bottom > top && isBlankRow(image, bottom, 0, width, bgRgb)) {
            bottom--;
        }
        int left = 0;
        while (left < width && isBlankColumn(image, left, top, bottom, bgRgb)) {
            left++;
        }
        int right = width - 1;
        while (right > left && isBlankColumn(image, right, top, bottom, bgRgb)) {
            right--;
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
     * 从四角采样估计背景色（抗非白底页面）。
     */
    private static int estimateBackground(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int sample = Math.min(CORNER_SAMPLE, Math.min(width, height));
        long r = 0;
        long g = 0;
        long b = 0;
        int count = 0;
        int[][] corners = {{0, 0}, {width - sample, 0}, {0, height - sample}, {width - sample, height - sample}};
        for (int[] corner : corners) {
            for (int dy = 0; dy < sample; dy++) {
                for (int dx = 0; dx < sample; dx++) {
                    int rgb = image.getRGB(corner[0] + dx, corner[1] + dy);
                    r += (rgb >> 16) & 0xFF;
                    g += (rgb >> 8) & 0xFF;
                    b += rgb & 0xFF;
                    count++;
                }
            }
        }
        return ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
    }

    private static boolean isBlankRow(BufferedImage image, int y, int fromX, int toX, int bgRgb) {
        int total = toX - fromX;
        int blank = 0;
        for (int x = fromX; x < toX; x++) {
            if (isNearBackground(image.getRGB(x, y), bgRgb)) {
                blank++;
            }
        }
        return (double) blank / total >= BLANK_RATIO;
    }

    private static boolean isBlankColumn(BufferedImage image, int x, int fromY, int toY, int bgRgb) {
        int total = toY - fromY + 1;
        int blank = 0;
        for (int y = fromY; y <= toY; y++) {
            if (isNearBackground(image.getRGB(x, y), bgRgb)) {
                blank++;
            }
        }
        return (double) blank / total >= BLANK_RATIO;
    }

    private static boolean isNearBackground(int rgb, int bgRgb) {
        return Math.abs(((rgb >> 16) & 0xFF) - ((bgRgb >> 16) & 0xFF)) <= COLOR_TOLERANCE
            && Math.abs(((rgb >> 8) & 0xFF) - ((bgRgb >> 8) & 0xFF)) <= COLOR_TOLERANCE
            && Math.abs((rgb & 0xFF) - (bgRgb & 0xFF)) <= COLOR_TOLERANCE;
    }
}
