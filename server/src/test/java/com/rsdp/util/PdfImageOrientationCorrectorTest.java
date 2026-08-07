package com.rsdp.util;

import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfImageOrientationCorrector} 单元测试。
 */
class PdfImageOrientationCorrectorTest {

    private static final int RED = Color.RED.getRGB();
    private static final int GREEN = Color.GREEN.getRGB();
    private static final int BLUE = Color.BLUE.getRGB();
    private static final int YELLOW = Color.YELLOW.getRGB();

    @Test
    void identityCtm_shouldReturnRawInstance() {
        BufferedImage raw = createCornerImage();
        assertThat(PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(1, 0, 0, 1, 0, 0)))
            .isSameAs(raw);
    }

    @Test
    void nullArguments_shouldReturnRaw() {
        BufferedImage raw = createCornerImage();
        assertThat(PdfImageOrientationCorrector.restoreDisplayOrientation(null, new Matrix())).isNull();
        assertThat(PdfImageOrientationCorrector.restoreDisplayOrientation(raw, null)).isSameAs(raw);
    }

    @Test
    void rotate90Ctm_shouldRotateCounterClockwise() {
        // PDF 中 [0 1 -1 0] 表示内容逆时针旋转 90°，原始位图需同步旋转还原显示方向
        BufferedImage raw = createCornerImage();
        BufferedImage corrected =
            PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(0, 1, -1, 0, 0, 0));

        assertThat(corrected.getWidth()).isEqualTo(3);
        assertThat(corrected.getHeight()).isEqualTo(2);
        // CCW 90°：左上→左下、右上→左上、左下→右下、右下→右上
        assertThat(corrected.getRGB(0, 1)).isEqualTo(RED);
        assertThat(corrected.getRGB(0, 0)).isEqualTo(GREEN);
        assertThat(corrected.getRGB(2, 1)).isEqualTo(BLUE);
        assertThat(corrected.getRGB(2, 0)).isEqualTo(YELLOW);
    }

    @Test
    void rotate180Ctm_shouldRotate180() {
        BufferedImage raw = createCornerImage();
        BufferedImage corrected =
            PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(-1, 0, 0, -1, 0, 0));

        assertThat(corrected.getWidth()).isEqualTo(2);
        assertThat(corrected.getHeight()).isEqualTo(3);
        assertThat(corrected.getRGB(1, 2)).isEqualTo(RED);
        assertThat(corrected.getRGB(0, 2)).isEqualTo(GREEN);
        assertThat(corrected.getRGB(1, 0)).isEqualTo(BLUE);
        assertThat(corrected.getRGB(0, 0)).isEqualTo(YELLOW);
    }

    @Test
    void rotate270Ctm_shouldRotateClockwise() {
        BufferedImage raw = createCornerImage();
        BufferedImage corrected =
            PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(0, -1, 1, 0, 0, 0));

        assertThat(corrected.getWidth()).isEqualTo(3);
        assertThat(corrected.getHeight()).isEqualTo(2);
        // CW 90°：左上→右上、右上→右下、左下→左上、右下→左下
        assertThat(corrected.getRGB(2, 0)).isEqualTo(RED);
        assertThat(corrected.getRGB(2, 1)).isEqualTo(GREEN);
        assertThat(corrected.getRGB(0, 0)).isEqualTo(BLUE);
        assertThat(corrected.getRGB(0, 1)).isEqualTo(YELLOW);
    }

    @Test
    void horizontalFlipCtm_shouldMirrorHorizontally() {
        BufferedImage raw = createCornerImage();
        BufferedImage corrected =
            PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(-1, 0, 0, 1, 0, 0));

        assertThat(corrected.getWidth()).isEqualTo(2);
        assertThat(corrected.getHeight()).isEqualTo(3);
        assertThat(corrected.getRGB(1, 0)).isEqualTo(RED);
        assertThat(corrected.getRGB(0, 0)).isEqualTo(GREEN);
    }

    @Test
    void verticalFlipCtm_shouldMirrorVertically() {
        BufferedImage raw = createCornerImage();
        BufferedImage corrected =
            PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(1, 0, 0, -1, 0, 0));

        assertThat(corrected.getWidth()).isEqualTo(2);
        assertThat(corrected.getHeight()).isEqualTo(3);
        assertThat(corrected.getRGB(0, 2)).isEqualTo(RED);
        assertThat(corrected.getRGB(1, 2)).isEqualTo(GREEN);
    }

    @Test
    void scaledRotationCtm_shouldCorrectIgnoringScale() {
        // 带缩放的 90° 旋转：[0 300 -200 0]，缩放分量应被归一化忽略
        BufferedImage raw = createCornerImage();
        BufferedImage corrected =
            PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(0, 300, -200, 0, 0, 0));

        assertThat(corrected.getWidth()).isEqualTo(3);
        assertThat(corrected.getHeight()).isEqualTo(2);
        assertThat(corrected.getRGB(0, 1)).isEqualTo(RED);
    }

    @Test
    void nonAxisAlignedCtm_shouldReturnRawUnchanged() {
        // 45° 旋转不在处理范围内，原样返回由 AI 裁剪路径兜底
        float s = (float) (Math.sqrt(2) / 2);
        BufferedImage raw = createCornerImage();
        assertThat(PdfImageOrientationCorrector.restoreDisplayOrientation(raw, new Matrix(s, s, -s, s, 0, 0)))
            .isSameAs(raw);
    }

    /**
     * 2x3 测试图：四角分别涂红（左上）、绿（右上）、蓝（左下）、黄（右下），其余白色。
     */
    private BufferedImage createCornerImage() {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 2, 3);
        g.dispose();
        image.setRGB(0, 0, RED);
        image.setRGB(1, 0, GREEN);
        image.setRGB(0, 2, BLUE);
        image.setRGB(1, 2, YELLOW);
        return image;
    }
}
