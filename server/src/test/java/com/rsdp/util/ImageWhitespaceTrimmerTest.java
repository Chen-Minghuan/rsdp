package com.rsdp.util;

import com.rsdp.dto.ProductBoundingBox;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImageWhitespaceTrimmer} 单元测试。
 */
class ImageWhitespaceTrimmerTest {

    @Test
    void cropRefine_shouldTrimWhitespaceAndRecoverFullProduct() throws IOException {
        // 400x400 白底页面，中心 200x200 深色产品
        BufferedImage page = createPage(400, 400, Color.WHITE);
        fillRect(page, 100, 100, 200, 200, new Color(30, 60, 120));

        // AI bbox 偏小（切进产品边缘）：相对坐标 (0.27, 0.27, 0.46x0.46)
        ProductBoundingBox roughBox = new ProductBoundingBox(0.27, 0.27, 0.46, 0.46);

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(page, roughBox, 0.03, 0.02, 0.9f);
        BufferedImage result = decode(jpeg);

        // 外扩 + 收紧 + 留白后，尺寸应接近完整产品 200px（±JPEG/取整误差）
        assertThat(result.getWidth()).isBetween(195, 215);
        assertThat(result.getHeight()).isBetween(195, 215);
        // 中心为产品色，角落为背景色 → 产品完整且边缘无多余内容
        assertNearColor(result.getRGB(result.getWidth() / 2, result.getHeight() / 2), new Color(30, 60, 120));
        assertNearColor(result.getRGB(1, 1), Color.WHITE);
    }

    @Test
    void cropRefine_shouldHandleUniformBlankPage() throws IOException {
        // 纯色页面：无法收紧，应原样输出而不报错
        BufferedImage page = createPage(300, 300, Color.WHITE);

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.1, 0.1, 0.5, 0.5), 0.03, 0.02, 0.9f);

        assertThat(jpeg).isNotEmpty();
        BufferedImage result = decode(jpeg);
        assertThat(result.getWidth()).isGreaterThan(0);
    }

    @Test
    void cropRefine_shouldTrimColoredBackground() throws IOException {
        // 非白底页面：背景色估计应适配浅灰背景
        Color bg = new Color(230, 230, 235);
        BufferedImage page = createPage(400, 400, bg);
        fillRect(page, 120, 120, 160, 160, new Color(200, 40, 40));

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.3, 0.3, 0.4, 0.4), 0.03, 0.02, 0.9f);
        BufferedImage result = decode(jpeg);

        assertNearColor(result.getRGB(result.getWidth() / 2, result.getHeight() / 2), new Color(200, 40, 40));
        assertNearColor(result.getRGB(1, 1), bg);
        assertThat(result.getWidth()).isBetween(150, 175);
    }

    @Test
    void cropRefine_shouldRejectInvalidInput() {
        BufferedImage page = createPage(100, 100, Color.WHITE);

        assertThatThrownBy(() -> ImageWhitespaceTrimmer.cropRefineToJpeg(
            null, new ProductBoundingBox(0.1, 0.1, 0.5, 0.5), 0.03, 0.02, 0.9f))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.1, 0.1, 0, 0.5), 0.03, 0.02, 0.9f))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageWhitespaceTrimmer.cropRefineToJpeg(page, null, 0.03, 0.02, 0.9f))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private BufferedImage createPage(int width, int height, Color bg) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private void fillRect(BufferedImage image, int x, int y, int w, int h, Color color) {
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    private BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private void assertNearColor(int rgb, Color expected) {
        int tolerance = 40; // JPEG 压缩容差
        assertThat(Math.abs(((rgb >> 16) & 0xFF) - expected.getRed())).isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(((rgb >> 8) & 0xFF) - expected.getGreen())).isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs((rgb & 0xFF) - expected.getBlue())).isLessThanOrEqualTo(tolerance);
    }
}
