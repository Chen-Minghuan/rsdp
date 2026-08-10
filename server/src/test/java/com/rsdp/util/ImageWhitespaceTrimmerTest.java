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

    @Test
    void cropRefine_shouldEstimateBackgroundRobustlyWhenCornerOnText() throws IOException {
        // 400x400 白底页面，中心深色产品；裁剪框左上角恰好落在一小块深色文字上。
        // 旧的均值背景估计会被带偏（灰）导致收紧失效；中位数估计抗单角离群，仍判白底
        BufferedImage page = createPage(400, 400, Color.WHITE);
        fillRect(page, 100, 100, 200, 200, new Color(30, 60, 120));
        fillRect(page, 76, 76, 5, 5, new Color(30, 30, 30));

        // bbox (0.22,0.22,0.56x0.56) + 0.03 外扩 → 裁剪区 (76,76,248,248)，文字块正在其左上角
        ProductBoundingBox bbox = new ProductBoundingBox(0.22, 0.22, 0.56, 0.56);
        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(page, bbox, 0.03, 0.02, 0.9f);
        BufferedImage result = decode(jpeg);

        // 背景色正确（白）→ 右/下侧空白被收紧：内容宽 ≈ 224（文字块到产品右缘）+ 留白
        // （若背景被污染为灰色则收紧完全失效，宽度会是 248+留白 ≈ 258）
        assertThat(result.getWidth()).isBetween(225, 245);
        // 留白边使用白色背景而非被污染的灰色
        assertNearColor(result.getRGB(result.getWidth() - 2, result.getHeight() - 2), Color.WHITE);
    }

    @Test
    void documentShouldCutBottomTextBand() throws IOException {
        // 白底卡片版式：产品图在上、说明文字在下（同一白底）→ document 模式应裁掉文字带
        BufferedImage page = createPage(400, 500, Color.WHITE);
        fillRect(page, 100, 50, 200, 250, new Color(30, 60, 120));
        // 文字行：零散小块（笔画感），两行
        for (int[] line : new int[][]{{340, 6}, {360, 6}}) {
            for (int x = 100; x <= 170; x += 35) {
                fillRect(page, x, line[0], 25, line[1], new Color(40, 40, 40));
            }
        }

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document());
        BufferedImage result = decode(jpeg);

        // 文字带被裁掉：结果高 ≈ 产品区 250 + 留白，远小于含文字的 325+
        assertThat(result.getHeight()).isBetween(240, 280);
        // 产品完整、底部边缘为白色留白而非文字
        assertNearColor(result.getRGB(result.getWidth() / 2, result.getHeight() / 2), new Color(30, 60, 120));
        assertNearColor(result.getRGB(result.getWidth() / 2, result.getHeight() - 3), Color.WHITE);
    }

    @Test
    void documentShouldKeepThinLegAboveTextBand() throws IOException {
        // 带细腿产品 + 底部文字：文字带裁掉，但空白隔断上方的细腿必须保留
        BufferedImage page = createPage(400, 500, Color.WHITE);
        fillRect(page, 100, 50, 200, 100, new Color(30, 60, 120));
        fillRect(page, 195, 150, 6, 120, new Color(30, 60, 120));
        for (int x = 100; x <= 170; x += 35) {
            fillRect(page, x, 300, 25, 6, new Color(40, 40, 40));
        }

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document());
        BufferedImage result = decode(jpeg);

        // 高度 ≈ 腿底（abs 270 - trim top 50 = 220）+ 留白，腿未被切
        assertThat(result.getHeight()).isBetween(210, 245);
        // 腿底附近（结果图下部）仍能找到产品色
        boolean legFound = false;
        for (int y = result.getHeight() - 25; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int rgb = result.getRGB(x, y);
                if (Math.abs((rgb & 0xFF) - 120) < 40 && Math.abs(((rgb >> 16) & 0xFF) - 30) < 40) {
                    legFound = true;
                    break;
                }
            }
            if (legFound) break;
        }
        assertThat(legFound).as("文字带上方有隔断的细腿不应被裁掉").isTrue();
    }

    @Test
    void documentShouldCutRightEdgeSlice() throws IOException {
        // AI 框越界：主产品 + 右侧通栏邻图切片（致密照片纹理），中间白隔断 → 切片应裁掉
        BufferedImage page = createPage(800, 400, Color.WHITE);
        fillRect(page, 100, 80, 400, 240, new Color(30, 60, 120));
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < 400; y++) {
            for (int x = 700; x < 790; x++) {
                page.setRGB(x, y, new Color(100 + random.nextInt(100), 60 + random.nextInt(80),
                    40 + random.nextInt(60)).getRGB());
            }
        }

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document());
        BufferedImage result = decode(jpeg);

        // 宽度 ≈ 产品区 400 + 留白，右侧切片（~100px）被裁掉
        assertThat(result.getWidth()).isBetween(390, 440);
        assertNearColor(result.getRGB(result.getWidth() - 3, result.getHeight() / 2), Color.WHITE);
    }

    @Test
    void documentShouldKeepSparseEdgeStructure() throws IOException {
        // 与主体隔断的独立细腿（纵向不满全图）不是邻图切片，必须保留
        BufferedImage page = createPage(800, 400, Color.WHITE);
        fillRect(page, 100, 80, 400, 240, new Color(30, 60, 120));
        fillRect(page, 700, 100, 8, 200, new Color(30, 60, 120));

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document());
        BufferedImage result = decode(jpeg);

        // 细腿所在列仍保留在结果内（宽度覆盖到腿）
        assertThat(result.getWidth()).isGreaterThan(550);
    }

    @Test
    void documentShouldCutSliceEvenWhenCornersMixed() throws IOException {
        // 混框（电视柜/边几实测案例）：白底图块+文字带，右侧拼接场景照片切片。
        // 四角天然不一致——切片切除必须在一致性闸门之前生效，且切掉后收紧/文字带重裁恢复工作
        BufferedImage page = createPage(900, 500, Color.WHITE);
        fillRect(page, 50, 100, 400, 200, new Color(30, 60, 120));
        for (int x = 100; x <= 205; x += 35) {
            fillRect(page, x, 380, 25, 6, new Color(40, 40, 40));
        }
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < 500; y++) {
            for (int x = 700; x < 900; x++) {
                page.setRGB(x, y, new Color(100 + random.nextInt(100), 60 + random.nextInt(80),
                    40 + random.nextInt(60)).getRGB());
            }
        }

        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(
            page, new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document());
        BufferedImage result = decode(jpeg);

        // 切片被切除：宽度 ≈ 产品区 400 + 留白（不再含 200px 切片与隔断）
        assertThat(result.getWidth()).isBetween(390, 440);
        // 文字带也被裁掉：高度 ≈ 产品区 200 + 留白
        assertThat(result.getHeight()).isBetween(190, 235);
        assertNearColor(result.getRGB(result.getWidth() - 3, result.getHeight() / 2), Color.WHITE);
    }

    @Test
    void anchorShouldNotCutIntoCoreBox() throws IOException {
        // 浅色产品（248 与白底差 7，在背景容差内）：无锚定时收紧会切进产品底部，
        // 锚定核心框后收紧只能清核心框外的边距
        BufferedImage page = createPage(400, 500, Color.WHITE);
        fillRect(page, 100, 100, 200, 300, new Color(248, 248, 248));

        ProductBoundingBox cropBox = new ProductBoundingBox(0.1, 0.1, 0.8, 0.9);
        ProductBoundingBox coreBox = new ProductBoundingBox(0.25, 0.2, 0.5, 0.6);
        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(page, cropBox, 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document(), coreBox);
        BufferedImage result = decode(jpeg);

        // 结果高度 ≈ 核心框高 300 + 留白，浅色产品底部不被吃掉
        assertThat(result.getHeight()).isBetween(295, 320);
        // 底部附近仍是产品浅色而非纯白（产品未被切）
        int bottomRgb = result.getRGB(result.getWidth() / 2, result.getHeight() - 8);
        assertThat(Math.abs((bottomRgb & 0xFF) - 248)).isLessThanOrEqualTo(12);
    }

    @Test
    void anchorRecropShouldCutTextBelowCoreBottom() throws IOException {
        // 说明文字在核心框底边以下（外扩边距混入）→ 文字带应被清掉，产品完整保留
        BufferedImage page = createPage(400, 600, Color.WHITE);
        fillRect(page, 100, 100, 200, 300, new Color(30, 60, 120));
        for (int x = 100; x <= 170; x += 35) {
            fillRect(page, x, 460, 25, 6, new Color(40, 40, 40));
        }

        ProductBoundingBox cropBox = new ProductBoundingBox(0.1, 0.1, 0.8, 0.9);
        ProductBoundingBox coreBox = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 0.5);
        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(page, cropBox, 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document(), coreBox);
        BufferedImage result = decode(jpeg);

        // 高度 ≈ 产品区 300 + 留白，核心框下的文字带被清掉
        assertThat(result.getHeight()).isBetween(290, 325);
        assertNearColor(result.getRGB(result.getWidth() / 2, result.getHeight() / 2), new Color(30, 60, 120));
    }

    @Test
    void anchorRecropShouldKeepTextInsideCoreBox() throws IOException {
        // 同样的文字，但当它位于核心框内部时（AI 把文字框进了 bbox），锚定保护不裁
        BufferedImage page = createPage(400, 600, Color.WHITE);
        fillRect(page, 100, 100, 200, 300, new Color(30, 60, 120));
        for (int x = 100; x <= 170; x += 35) {
            fillRect(page, x, 460, 25, 6, new Color(40, 40, 40));
        }

        ProductBoundingBox cropBox = new ProductBoundingBox(0.1, 0.1, 0.8, 0.9);
        // 核心框底边 abs 490，把文字（460）包进框内
        ProductBoundingBox coreBox = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 0.65);
        byte[] jpeg = ImageWhitespaceTrimmer.cropRefineToJpeg(page, cropBox, 0.0, 0.02, 0.9f,
            ImageWhitespaceTrimmer.TrimOptions.document(), coreBox);
        BufferedImage result = decode(jpeg);

        // 高度 ≈ 核心框高 390 + 少量边距/留白，文字保留在结果中
        assertThat(result.getHeight()).isGreaterThan(370);
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
