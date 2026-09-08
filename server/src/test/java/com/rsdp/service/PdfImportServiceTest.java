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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        setField("maxPages", 50);
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

    @Test
    void importPdf_shouldRetryNullBBoxPageIndividually() throws IOException {
        // 批检测返回 product 页但产品 bbox 全为 null（模型输出格式抖动）→ 单页重试恢复
        byte[] pdfBytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        DocumentProductRegion brokenPage = new DocumentProductRegion();
        brokenPage.setPageType("product");
        brokenPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(null, "SF", null, null)
        ));
        DocumentProductRegion recoveredPage = new DocumentProductRegion();
        recoveredPage.setPageType("product");
        recoveredPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));

        when(visionService.detectPageRegions(any(), any()))
            .thenReturn(List.of(brokenPage))
            .thenReturn(List.of(recoveredPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST08", "taskId", "TASK-TEST08"));

        DocumentImportResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).containsExactly("RSPU-TEST08");
        verify(visionService, times(2)).detectPageRegions(any(), any());
    }

    @Test
    void expandBox_shouldExpandRelativeToBoxSize() {
        // 相对 bbox 自身宽高的 5%：0.4 宽的框水平外扩 0.02，而不是相对整页的固定 0.03
        ProductBoundingBox expanded =
            PdfImportService.expandBox(new ProductBoundingBox(0.2, 0.2, 0.4, 0.4), 0.05);

        assertThat(expanded.getX()).isCloseTo(0.18, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(expanded.getY()).isCloseTo(0.18, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(expanded.getWidth()).isCloseTo(0.44, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(expanded.getHeight()).isCloseTo(0.44, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void expandBox_shouldClampAtPageEdges() {
        // 左上边缘：外扩后 x/y 钳制为 0，宽高不超过页面
        ProductBoundingBox edge =
            PdfImportService.expandBox(new ProductBoundingBox(0.0, 0.0, 0.5, 0.5), 0.05);
        assertThat(edge.getX()).isEqualTo(0.0);
        assertThat(edge.getY()).isEqualTo(0.0);
        assertThat(edge.getWidth()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(1e-9));

        // 右下边缘：x+w 不超过 1
        ProductBoundingBox far =
            PdfImportService.expandBox(new ProductBoundingBox(0.9, 0.9, 0.1, 0.1), 0.05);
        assertThat(far.getX() + far.getWidth()).isLessThanOrEqualTo(1.0);
        assertThat(far.getY() + far.getHeight()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void recoverCutEdges_shouldRecoverContinuousProductBelowCore() {
        // 核心框底边切进产品（abs 300），产品实际延伸到 abs 349，下方是隔断+说明文字
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 200, 250, new java.awt.Color(30, 60, 120));
        for (int x = 100; x <= 170; x += 35) {
            fillRectOnPage(page, x, 460, 25, 6, new java.awt.Color(40, 40, 40));
        }
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 1.0 / 3);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of());

        // 底边恢复到产品真实底部 abs 350（隔断前的最后一行内容），文字区不被并入
        assertThat(recovered.getY() + recovered.getHeight())
            .isCloseTo(350.0 / 600, org.assertj.core.data.Offset.offset(0.01));
        assertThat(recovered.getX()).isEqualTo(core.getX());
        assertThat(recovered.getWidth()).isEqualTo(core.getWidth());
    }

    @Test
    void recoverCutEdges_shouldNotExtendWhenEdgeIsBackground() {
        // 核心框底边落在产品之外的空白区（框已包全产品）→ 无切断信号，原样返回
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 200, 200, new java.awt.Color(30, 60, 120));
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 0.5);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of());

        assertThat(recovered.getY() + recovered.getHeight()).isEqualTo(1.0 / 6 + 0.5);
    }

    @Test
    void recoverCutEdges_shouldStopBeforeSiblingBox() {
        // 下方延伸撞上兄弟产品框（abs 350 起）→ 在兄弟框边界前停止，不侵占其区域
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 200, 400, new java.awt.Color(30, 60, 120));
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 1.0 / 3);
        ProductBoundingBox sibling = new ProductBoundingBox(0.25, 350.0 / 600, 0.5, 0.2);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of(sibling));

        assertThat(recovered.getY() + recovered.getHeight())
            .isLessThanOrEqualTo(350.0 / 600 + 0.001);
    }

    @Test
    void recoverCutEdges_shouldRecoverRightEdge() {
        // 核心框右边切进产品（abs 300），产品向右延伸到 abs 349（在外延上限内）
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 250, 200, new java.awt.Color(30, 60, 120));
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 200.0 / 400, 200.0 / 600);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of());

        assertThat(recovered.getX() + recovered.getWidth())
            .isCloseTo(350.0 / 400, org.assertj.core.data.Offset.offset(0.01));
    }

    private java.awt.image.BufferedImage createTestPage(int width, int height, java.awt.Color bg) {
        java.awt.image.BufferedImage image =
            new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private void fillRectOnPage(java.awt.image.BufferedImage image, int x, int y, int w, int h,
                                java.awt.Color color) {
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(x, y, w, h);
        g.dispose();
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
