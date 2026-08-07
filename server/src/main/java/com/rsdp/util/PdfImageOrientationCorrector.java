package com.rsdp.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.util.Matrix;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * PDF 嵌入图方向校正器。
 *
 * <p>画册 PDF 常把横拍照片通过内容流变换矩阵（CTM 带 90°/270° 旋转或镜像翻转）
 * 摆放到页面上，而 {@code PDImageXObject.getImage()} 返回的是存储方向的原始位图，
 * 直接抽取会得到旋转/镜像后的图片。本类分解 CTM 的旋转/翻转分量，
 * 把原始位图还原为页面上的显示方向。</p>
 *
 * <p>仅处理轴对齐（90° 的整数倍旋转 ± 镜像）的变换——画册排版几乎都属于此类，
 * 且这类变换可以无损重排像素；任意角度的旋转不做处理（原样返回，由 AI 裁剪路径兜底）。</p>
 */
@Slf4j
public final class PdfImageOrientationCorrector {

    /**
     * 矩阵元素吸附到 0/±1 的容差。
     */
    private static final double SNAP_TOLERANCE = 0.05;

    private PdfImageOrientationCorrector() {
    }

    /**
     * 将嵌入图原始位图还原为页面显示方向。
     *
     * @param raw 嵌入图原始位图（存储方向）
     * @param ctm 绘制该图时的当前变换矩阵
     * @return 显示方向的位图；无需校正或无法识别变换时返回原图
     */
    public static BufferedImage restoreDisplayOrientation(BufferedImage raw, Matrix ctm) {
        if (raw == null || ctm == null) {
            return raw;
        }
        // 归一化：去除缩放分量，只保留旋转/翻转
        double a0 = ctm.getValue(0, 0);
        double b0 = ctm.getValue(0, 1);
        double c0 = ctm.getValue(1, 0);
        double d0 = ctm.getValue(1, 1);
        double scaleX = Math.hypot(a0, b0);
        double scaleY = Math.hypot(c0, d0);
        if (scaleX < 1e-6 || scaleY < 1e-6) {
            return raw;
        }
        double a = snap(a0 / scaleX);
        double b = snap(b0 / scaleX);
        double c = snap(c0 / scaleY);
        double d = snap(d0 / scaleY);
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) {
            log.debug("嵌入图 CTM 含非轴对齐旋转（[{} {} {} {}]），不做方向校正", a0, b0, c0, d0);
            return raw;
        }

        // 显示方向 = F·M·F 作用于原始位图（M=[[a,c],[b,d]] 为 CTM 线性部分的标准矩阵形式，
        // F 为垂直翻转：图像首行绘制在单位正方形 y=1 处一次翻转、页面 y 轴向上到屏幕 y 轴向下再一次翻转）。
        // 对角符号规则 (FMF)[i][j] = M[i][j]·f[i]·f[j]，f=(1,-1)，得 S=[[a,-c],[-b,d]]
        double s00 = a;
        double s01 = -c;
        double s10 = -b;
        double s11 = d;
        if (s00 == 1 && s01 == 0 && s10 == 0 && s11 == 1) {
            return raw;
        }
        return transform(raw, s00, s01, s10, s11);
    }

    /**
     * 将矩阵元素吸附到最近的 0/±1；偏离轴对齐变换时返回 NaN。
     */
    private static double snap(double value) {
        if (Math.abs(value) <= SNAP_TOLERANCE) {
            return 0;
        }
        if (Math.abs(value - 1) <= SNAP_TOLERANCE) {
            return 1;
        }
        if (Math.abs(value + 1) <= SNAP_TOLERANCE) {
            return -1;
        }
        return Double.NaN;
    }

    /**
     * 按 2x2 线性矩阵重排像素（仅轴对齐变换，输出尺寸为原图宽高或其互换）。
     */
    private static BufferedImage transform(BufferedImage raw,
                                           double s00, double s01, double s10, double s11) {
        int width = raw.getWidth();
        int height = raw.getHeight();
        AffineTransform at = new AffineTransform(s00, s10, s01, s11, 0, 0);

        // 计算变换后包围盒并平移到原点
        double[] corners = {0, 0, width, 0, 0, height, width, height};
        double[] mapped = new double[8];
        at.transform(corners, 0, mapped, 0, 4);
        double minX = Math.min(Math.min(mapped[0], mapped[2]), Math.min(mapped[4], mapped[6]));
        double maxX = Math.max(Math.max(mapped[0], mapped[2]), Math.max(mapped[4], mapped[6]));
        double minY = Math.min(Math.min(mapped[1], mapped[3]), Math.min(mapped[5], mapped[7]));
        double maxY = Math.max(Math.max(mapped[1], mapped[3]), Math.max(mapped[5], mapped[7]));

        int newWidth = Math.max(1, (int) Math.round(maxX - minX));
        int newHeight = Math.max(1, (int) Math.round(maxY - minY));
        BufferedImage output = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate(-minX, -minY);
        g.drawImage(raw, at, null);
        g.dispose();
        return output;
    }
}
