package com.rsdp.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfEmbeddedImageExtractor} 单元测试。
 */
class PdfEmbeddedImageExtractorTest {

    private static final double MIN_AREA_RATIO = 0.20;
    private static final int MIN_PIXEL_EDGE = 200;

    @Test
    void extract_shouldFindLargeDrawnImage() throws IOException {
        // A4 页面嵌入一张 300x400 像素、绘制尺寸 400x600 点的图（面积占比 ~48%）
        byte[] pdf = createPdfWithImage(300, 400, 400, 600);

        Map<Integer, List<BufferedImage>> result =
            PdfEmbeddedImageExtractor.extractLargeImages(pdf, MIN_AREA_RATIO, MIN_PIXEL_EDGE);

        assertThat(result).containsKey(0);
        assertThat(result.get(0)).hasSize(1);
        assertThat(result.get(0).get(0).getWidth()).isEqualTo(300);
    }

    @Test
    void extract_shouldFilterSmallDrawnImage() throws IOException {
        // 绘制尺寸仅 80x80 点（Logo 级别，面积占比 ~1%）→ 应被过滤
        byte[] pdf = createPdfWithImage(300, 400, 80, 80);

        Map<Integer, List<BufferedImage>> result =
            PdfEmbeddedImageExtractor.extractLargeImages(pdf, MIN_AREA_RATIO, MIN_PIXEL_EDGE);

        assertThat(result).isEmpty();
    }

    @Test
    void extract_shouldFilterSmallPixelImage() throws IOException {
        // 绘制面积够大但原始像素仅 50x60（缩略图放大）→ 应被像素门槛过滤
        byte[] pdf = createPdfWithImage(50, 60, 400, 600);

        Map<Integer, List<BufferedImage>> result =
            PdfEmbeddedImageExtractor.extractLargeImages(pdf, MIN_AREA_RATIO, MIN_PIXEL_EDGE);

        assertThat(result).isEmpty();
    }

    @Test
    void extract_shouldHandleBlankPdf() throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdf = out.toByteArray();
        }

        Map<Integer, List<BufferedImage>> result =
            PdfEmbeddedImageExtractor.extractLargeImages(pdf, MIN_AREA_RATIO, MIN_PIXEL_EDGE);

        assertThat(result).isEmpty();
    }

    private byte[] createPdfWithImage(int imgPixelW, int imgPixelH, int drawW, int drawH) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            BufferedImage image = new BufferedImage(imgPixelW, imgPixelH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(60, 120, 180));
            g.fillRect(0, 0, imgPixelW, imgPixelH);
            g.dispose();

            PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.drawImage(pdImage, 50, 100, drawW, drawH);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
