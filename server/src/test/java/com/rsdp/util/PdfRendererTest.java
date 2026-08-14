package com.rsdp.util;

import com.rsdp.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ---------- 首页渲染为 PNG（户型图 PDF 支持，v3.0 §8 P2） ----------

    @Test
    void renderFirstPageAsPng_multiPagePdf_shouldRenderOnlyFirstPage() throws IOException {
        byte[] pngBytes = PdfRenderer.renderFirstPageAsPng(createPdfBytes(3), 72);

        // PNG 魔数
        assertThat(pngBytes).startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(image.getWidth()).isGreaterThan(0);
        assertThat(image.getHeight()).isGreaterThan(0);
    }

    @Test
    void renderFirstPageAsPng_encryptedPdf_shouldThrowBusinessException() throws IOException {
        byte[] pdfBytes = createEncryptedPdfBytes();

        assertThatThrownBy(() -> PdfRenderer.renderFirstPageAsPng(pdfBytes, 72))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("加密");
    }

    @Test
    void renderFirstPageAsPng_invalidPdf_shouldThrowBusinessException() {
        assertThatThrownBy(() -> PdfRenderer.renderFirstPageAsPng("not-a-pdf".getBytes(), 72))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无法解析");
    }

    @Test
    void renderFirstPageAsPng_noPagePdf_shouldThrowBusinessException() throws IOException {
        assertThatThrownBy(() -> PdfRenderer.renderFirstPageAsPng(createPdfBytes(0), 72))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("没有任何页面");
    }

    private byte[] createEncryptedPdfBytes() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
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
