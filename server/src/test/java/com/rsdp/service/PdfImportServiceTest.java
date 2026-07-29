package com.rsdp.service;

import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.dto.response.DocumentImportResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link PdfImportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PdfImportServiceTest {

    @Mock
    private VisionService visionService;

    @Mock
    private ProductService productService;

    @InjectMocks
    private PdfImportService pdfImportService;

    @BeforeEach
    void setUp() throws Exception {
        setField("maxFileSizeMb", 50);
        setField("maxPages", 200);
        setField("renderDpi", 72f);
        setField("detectBatchSize", 5);
        setField("outputQuality", 0.9f);
        setField("embeddedMinAreaRatio", 0.20);
        setField("embeddedMinPixelEdge", 200);
    }

    @Test
    void importPdf_shouldCreateEntriesForProductPages() throws IOException {
        byte[] pdfBytes = createPdfBytes(2);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF")
        ));
        DocumentProductRegion coverPage = new DocumentProductRegion();
        coverPage.setPageType("cover");
        coverPage.setProducts(List.of());

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage, coverPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST01", "taskId", "TASK-TEST01"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getProductPages()).isEqualTo(1);
        assertThat(result.getTotalProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST01");
        assertThat(result.getTaskIds()).containsExactly("TASK-TEST01");
    }

    @Test
    void importPdf_shouldUseCategoryHintWhenAiReturnsNull() throws IOException {
        byte[] pdfBytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), null)
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), eq("TB")))
            .thenReturn(Map.of("rspuId", "RSPU-TEST02", "taskId", "TASK-TEST02"));

        DocumentImportResult result = pdfImportService.importPdf(file, "TB");

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST02");
    }

    @Test
    void importPdf_shouldHandleEmptyPdf() throws IOException {
        byte[] pdfBytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion unknownPage = new DocumentProductRegion();
        unknownPage.setPageType("unknown");
        unknownPage.setProducts(List.of());

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(unknownPage));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getProductPages()).isEqualTo(0);
        assertThat(result.getTotalProducts()).isEqualTo(0);
        assertThat(result.getSuccessCount()).isEqualTo(0);
    }

    @Test
    void importPdf_shouldRetryUnknownPageIndividually() throws IOException {
        byte[] pdfBytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF")
        ));

        // 批检测整体失败 → 整页降级 unknown；单页重试时恢复为产品页
        when(visionService.detectPageRegions(any(), any()))
            .thenThrow(new RuntimeException("AI 服务超时"))
            .thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST03", "taskId", "TASK-TEST03"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getProductPages()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST03");
    }

    @Test
    void importPdf_shouldUseEmbeddedImageWhenAiDetectsNoProduct() throws IOException {
        // PDF 含一张大面积嵌入产品图；AI 判定为产品页但没检出任何 bbox
        // → 嵌入图直取兜底，仍能创建录入任务（完整度保障）
        byte[] pdfBytes = createPdfWithLargeEmbeddedImage();
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of());

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST04", "taskId", "TASK-TEST04"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getProductPages()).isEqualTo(1);
        assertThat(result.getTotalProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST04");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = PdfImportService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(pdfImportService, value);
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

    private byte[] createPdfWithLargeEmbeddedImage() throws IOException {
        try (PDDocument document = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page =
                new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            document.addPage(page);

            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(300, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(new java.awt.Color(60, 120, 180));
            g.fillRect(0, 0, 300, 400);
            g.dispose();

            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, image);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream =
                     new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                // 绘制尺寸 400x600 点，面积占比约 48%，满足大图阈值
                contentStream.drawImage(pdImage, 50, 100, 400, 600);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
