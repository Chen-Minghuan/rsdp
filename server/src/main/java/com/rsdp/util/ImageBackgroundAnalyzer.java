package com.rsdp.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 图片背景分析器：规则判别"单品图 vs 场景图"。
 *
 * <p>画册中的单品图几乎都是白底或浅色纯色摄影棚背景，图片外圈（边框带）
 * 呈现"大面积近白"或"高亮度低方差"的特征；场景图（房间/展厅效果图）的
 * 边框带通常跨越墙面、地面、家具边缘，颜色和亮度变化大。</p>
 *
 * <p>判别是保守的：只有边框带既不够白也不够均匀才判为场景图，
 * 灰底等非常规但均匀的摄影棚背景仍按单品图保留，宁漏不误杀。</p>
 */
public final class ImageBackgroundAnalyzer {

    /**
     * 分析前降采样的长边像素，足够统计背景特征且成本低。
     */
    private static final int SAMPLE_MAX_EDGE = 64;

    /**
     * 边框带厚度占图片宽高的比例。
     */
    private static final double BORDER_RATIO = 0.08;

    /**
     * 近白像素阈值：RGB 三通道均不低于该值视为近白。
     */
    private static final int NEAR_WHITE_MIN_CHANNEL = 235;

    /**
     * 边框带近白像素占比达到该值即判为白底单品图。
     */
    private static final double NEAR_WHITE_RATIO_MIN = 0.65;

    /**
     * 均匀浅色背景：边框带亮度标准差上限。
     */
    private static final double UNIFORM_STDDEV_MAX = 35.0;

    /**
     * 均匀浅色背景：边框带亮度均值下限（浅灰棚拍背景约 180+）。
     */
    private static final double UNIFORM_MEAN_MIN = 170.0;

    private ImageBackgroundAnalyzer() {
    }

    /**
     * 判断图片是否像场景图（房间/展厅效果图而非单品拍摄图）。
     *
     * @param image 待判别图片
     * @return true 表示边框带既非大面积近白也非均匀浅色，按场景图处理
     */
    public static boolean looksLikeSceneImage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        BufferedImage sample = downscale(image);
        int width = sample.getWidth();
        int height = sample.getHeight();
        int borderX = Math.max(2, (int) Math.round(width * BORDER_RATIO));
        int borderY = Math.max(2, (int) Math.round(height * BORDER_RATIO));

        int count = 0;
        int nearWhite = 0;
        double sum = 0;
        double sumSq = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean inBorder = x < borderX || x >= width - borderX
                    || y < borderY || y >= height - borderY;
                if (!inBorder) {
                    continue;
                }
                int rgb = sample.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= NEAR_WHITE_MIN_CHANNEL && g >= NEAR_WHITE_MIN_CHANNEL && b >= NEAR_WHITE_MIN_CHANNEL) {
                    nearWhite++;
                }
                double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
                sum += luminance;
                sumSq += luminance * luminance;
                count++;
            }
        }
        if (count == 0) {
            return false;
        }

        double nearWhiteRatio = (double) nearWhite / count;
        if (nearWhiteRatio >= NEAR_WHITE_RATIO_MIN) {
            return false;
        }
        double mean = sum / count;
        double stddev = Math.sqrt(Math.max(0.0, sumSq / count - mean * mean));
        // 均匀浅色边框带（灰底棚拍等）仍视为单品图，否则判为场景图
        return stddev > UNIFORM_STDDEV_MAX || mean < UNIFORM_MEAN_MIN;
    }

    /**
     * 等比降采样到 {@link #SAMPLE_MAX_EDGE} 以内，小图原样返回。
     */
    private static BufferedImage downscale(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (Math.max(width, height) <= SAMPLE_MAX_EDGE) {
            return image;
        }
        double ratio = (double) SAMPLE_MAX_EDGE / Math.max(width, height);
        int newWidth = Math.max(1, (int) Math.round(width * ratio));
        int newHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage output = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return output;
    }
}
