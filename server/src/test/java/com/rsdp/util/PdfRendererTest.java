package com.rsdp.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfRenderer} 单元测试。
 */
class PdfRendererTest {

    @Test
    void renderPages_shouldRenderAllPages() throws IOException {
        byte[] pdfBytes = createPdfBytes(3);

        List<BufferedImage> images;
        try (ByteArrayInputStream in = new ByteArrayInputStream(pdfBytes)) {
            images = PdfRenderer.renderPages(in, 72);
        }

        assertThat(images).hasSize(3);
        for (BufferedImage image : images) {
            assertThat(image.getWidth()).isGreaterThan(0);
            assertThat(image.getHeight()).isGreaterThan(0);
        }
    }

    @Test
    void renderPages_shouldRenderFromBytes() throws IOException {
        byte[] pdfBytes = createPdfBytes(2);

        List<BufferedImage> images = PdfRenderer.renderPages(pdfBytes, 72);

        assertThat(images).hasSize(2);
    }

    private byte[] createPdfBytes(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
