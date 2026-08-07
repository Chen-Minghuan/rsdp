package com.rsdp.service;

import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.OcrResult;
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
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));
        DocumentProductRegion coverPage = new DocumentProductRegion();
        coverPage.setPageType("cover");
        coverPage.setProducts(List.of());

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage, coverPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
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
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), null, null, null)
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), eq("TB"), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST02", "taskId", "TASK-TEST02"));

        DocumentImportResult result = pdfImportService.importPdf(file, "TB");

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST02");
    }

    @Test
    void importPdf_shouldPassNearbyTextToEntry() throws IOException {
        byte[] pdfBytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        OcrResult nearbyText = new OcrResult();
        nearbyText.setProductName("兰卡沙发");
        nearbyText.setModelNumber("LK-2450");

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", nearbyText, null)
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST04", "taskId", "TASK-TEST04"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        org.mockito.ArgumentCaptor<OcrResult> ocrCaptor = org.mockito.ArgumentCaptor.forClass(OcrResult.class);
        org.mockito.Mockito.verify(productService).createEntryFromStream(any(), anyString(), anyLong(),
            anyString(), ocrCaptor.capture());
        assertThat(ocrCaptor.getValue().getProductName()).isEqualTo("兰卡沙发");
        assertThat(ocrCaptor.getValue().getModelNumber()).isEqualTo("LK-2450");
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
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));

        // 批检测整体失败 → 整页降级 unknown；单页重试时恢复为产品页
        when(visionService.detectPageRegions(any(), any()))
            .thenThrow(new RuntimeException("AI 服务超时"))
            .thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
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
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST04", "taskId", "TASK-TEST04"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getProductPages()).isEqualTo(1);
        assertThat(result.getTotalProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST04");
    }

    @Test
    void importPdf_shouldSkipSceneProductsWithoutText() throws IOException {
        // AI 标记 imageKind=scene 且无说明文字的场景点缀产品不建档，同页单品图照常录入
        byte[] pdfBytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, "standalone"),
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.5, 0.5, 0.4, 0.4), "SF", null, "scene")
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST05", "taskId", "TASK-TEST05"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getTotalProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST05");
    }

    @Test
    void importPdf_shouldKeepSceneProductsWithText() throws IOException {
        // 场景中完整可见且带说明文字（nearbyText）的产品保留，照常裁剪录入
        byte[] pdfBytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        OcrResult sceneText = new OcrResult();
        sceneText.setProductName("云朵沙发");
        sceneText.setDimensionText("2200×950×860mm");

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, "standalone"),
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.5, 0.5, 0.4, 0.4), "SF", sceneText, "scene")
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST07", "taskId", "TASK-TEST07"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getTotalProducts()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(2);
    }

    @Test
    void importPdf_shouldSkipSceneEmbeddedImageAndFallbackToAiCrop() throws IOException {
        // 嵌入大图边框带杂乱（疑似场景图）→ 剔除后嵌入图数量不足，回落 AI bbox 裁剪路径
        byte[] pdfBytes = createPdfWithSceneLikeEmbeddedImage();
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, "standalone")
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST06", "taskId", "TASK-TEST06"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getTotalProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST06");
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

            // 白底 + 中心产品色块：模拟单品图嵌入（边框带近白，不会被场景规则误杀）
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(300, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 300, 400);
            g.setColor(new java.awt.Color(60, 120, 180));
            g.fillRect(60, 100, 180, 200);
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

    private byte[] createPdfWithSceneLikeEmbeddedImage() throws IOException {
        try (PDDocument document = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page =
                new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            document.addPage(page);

            // 上墙下地 + 杂色家具：边框带亮度方差大，触发场景图规则
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(300, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(new java.awt.Color(210, 200, 180));
            g.fillRect(0, 0, 300, 200);
            g.setColor(new java.awt.Color(70, 50, 35));
            g.fillRect(0, 200, 300, 200);
            g.setColor(new java.awt.Color(40, 90, 60));
            g.fillRect(100, 150, 100, 120);
            g.dispose();

            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, image);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream =
                     new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                contentStream.drawImage(pdImage, 50, 100, 400, 600);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
