package com.rsdp.util;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageBackgroundAnalyzer} 单元测试。
 */
class ImageBackgroundAnalyzerTest {

    @Test
    void whiteBackgroundProductPhoto_shouldNotBeScene() {
        // 白底单品图：白色背景 + 中心深色产品
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 200);
        g.setColor(new Color(80, 60, 50));
        g.fillRect(60, 60, 80, 80);
        g.dispose();

        assertThat(ImageBackgroundAnalyzer.looksLikeSceneImage(image)).isFalse();
    }

    @Test
    void uniformLightGrayBackground_shouldNotBeScene() {
        // 浅灰摄影棚背景：非近白但均匀高亮，仍按单品图保留
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(228, 228, 228));
        g.fillRect(0, 0, 200, 200);
        g.setColor(new Color(120, 90, 60));
        g.fillRect(70, 70, 60, 60);
        g.dispose();

        assertThat(ImageBackgroundAnalyzer.looksLikeSceneImage(image)).isFalse();
    }

    @Test
    void noisyColorfulImage_shouldBeScene() {
        // 场景图特征：边框带颜色杂乱（墙面/地面/家具交错）
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                image.setRGB(x, y, new Color(random.nextInt(256), random.nextInt(256),
                    random.nextInt(256)).getRGB());
            }
        }

        assertThat(ImageBackgroundAnalyzer.looksLikeSceneImage(image)).isTrue();
    }

    @Test
    void splitRoomLikeImage_shouldBeScene() {
        // 上墙下地的房间构图：边框带亮度方差大
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(210, 200, 180));
        g.fillRect(0, 0, 200, 100);
        g.setColor(new Color(70, 50, 35));
        g.fillRect(0, 100, 200, 100);
        g.dispose();

        assertThat(ImageBackgroundAnalyzer.looksLikeSceneImage(image)).isTrue();
    }

    @Test
    void nullImage_shouldNotBeScene() {
        assertThat(ImageBackgroundAnalyzer.looksLikeSceneImage(null)).isFalse();
    }
}
