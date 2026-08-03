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

    @Test
    void cropRefine_conservativeShouldKeepThinLeg() throws IOException {
        // 400x400 白底：200x100 深色柜体 + 柜体下方 3px 宽细腿（低占比内容）
        BufferedImage page = createPage(400, 400, Color.WHITE);
        fillRect(page, 100, 150, 200, 100, new Color(30, 60, 120));
        fillRect(page, 195, 250, 3, 100, new Color(30, 60, 120));

        ProductBoundingBox bbox = new ProductBoundingBox(0.25, 0.375, 0.5, 0.5);
        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, bbox, 0.05, 0.02, 0.9f, ImageWhitespaceTrimmer.TrimOptions.conservative());
        BufferedImage result = decode(jpeg);

        // 细腿所在区域应保留：结果图底部附近仍能找到产品色
        boolean legFound = false;
        for (int y = result.getHeight() - 30; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int rgb = result.getRGB(x, y);
                if (Math.abs((rgb & 0xFF) - 120) < 40 && Math.abs(((rgb >> 16) & 0xFF) - 30) < 40) {
                    legFound = true;
                    break;
                }
            }
            if (legFound) break;
        }
        assertThat(legFound).as("保守模式不应切掉细腿").isTrue();
    }

    @Test
    void cropRefine_conservativeShouldSkipTrimOnSceneBackground() throws IOException {
        // 场景背景：四角颜色差异大（四象限异色），中心深色产品
        BufferedImage page = createPage(400, 400, Color.WHITE);
        fillRect(page, 0, 0, 200, 200, new Color(200, 50, 50));
        fillRect(page, 200, 0, 200, 200, new Color(50, 200, 50));
        fillRect(page, 0, 200, 200, 200, new Color(50, 50, 200));
        fillRect(page, 200, 200, 200, 200, new Color(200, 200, 50));
        fillRect(page, 120, 120, 160, 160, new Color(40, 40, 40));

        ProductBoundingBox bbox = new ProductBoundingBox(0.2, 0.2, 0.4, 0.4);
        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, bbox, 0.05, 0.02, 0.9f, ImageWhitespaceTrimmer.TrimOptions.conservative());
        BufferedImage result = decode(jpeg);

        // 非纯色背景跳过收紧：结果尺寸 ≈ 外扩裁剪框（0.5*400=200）+ 留白，明显大于产品本身 160px
        assertThat(result.getWidth()).isBetween(195, 215);
        assertThat(result.getHeight()).isBetween(195, 215);
    }

    @Test
    void cropRefine_conservativeShouldLimitTrimRatio() throws IOException {
        // 浅色产品（与白色背景差异 < 容差）：收紧上限 25% 防止产品被过度切小
        BufferedImage page = createPage(400, 400, Color.WHITE);
        fillRect(page, 100, 100, 200, 200, new Color(248, 248, 248));

        ProductBoundingBox bbox = new ProductBoundingBox(0.25, 0.25, 0.5, 0.5);
        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, bbox, 0.05, 0.02, 0.9f, ImageWhitespaceTrimmer.TrimOptions.conservative());
        BufferedImage result = decode(jpeg);

        // 外扩裁剪框 240px，每边最多收紧 25%（60px）→ 收紧后 >= 120px，加留白后 >= 124px
        assertThat(result.getWidth()).isGreaterThanOrEqualTo(120);
        assertThat(result.getHeight()).isGreaterThanOrEqualTo(120);
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
