package com.rsdp.service;

import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.dto.response.DocumentImportFailure;
import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.exception.BusinessException;
import com.rsdp.util.ImageWhitespaceTrimmer;
import com.rsdp.util.PdfEmbeddedImageExtractor;
import com.rsdp.util.PdfFileValidator;
import com.rsdp.util.PdfRenderer;
import com.rsdp.util.ProductBoxRefiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.rsdp.util.IdGenerator;

/**
 * PDF 产品目录批量导入服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfImportService {

    private final VisionService visionService;
    private final ProductService productService;

    @Value("${rsdp.document-import.pdf.max-file-size-mb:50}")
    private int maxFileSizeMb;

    @Value("${rsdp.document-import.pdf.max-pages:200}")
    private int maxPages;

    @Value("${rsdp.document-import.pdf.render-dpi:200}")
    private float renderDpi;

    @Value("${rsdp.document-import.pdf.detect-batch-size:5}")
    private int detectBatchSize;

    @Value("${rsdp.document-import.pdf.output-quality:0.9}")
    private float outputQuality;

    /**
     * 嵌入图直取：图片绘制面积占页面面积的最小比例。
     */
    @Value("${rsdp.document-import.pdf.embedded-image.min-area-ratio:0.20}")
    private double embeddedMinAreaRatio;

    /**
     * 嵌入图直取：图片原始像素的最小边长。
     */
    @Value("${rsdp.document-import.pdf.embedded-image.min-pixel-edge:200}")
    private int embeddedMinPixelEdge;

    /**
     * AI 检测用图长边上限。qwen-vl 支持高分辨率输入，
     * 1568px 相比 1024px 能显著提升 bbox 坐标精度。
     */
    private static final int DETECT_IMAGE_MAX_EDGE = 1568;

    /**
     * 裁剪前 bbox 外扩比例（相对页面宽高），防止 AI 低估边界切断产品。
     */
    private static final double CROP_EXPAND_RATIO = 0.03;

    /**
     * 白边收紧后的留白比例（相对内容宽高）。
     */
    private static final double CROP_PAD_RATIO = 0.02;

    /**
     * 导入 PDF 文件，自动识别产品页、裁剪产品图并创建 RSPU 录入任务。
     *
     * @param file         PDF 文件
     * @param categoryHint 品类提示，可为空
     * @return 导入批次结果
     * @throws IOException 文件处理失败
     */
    public DocumentImportResult importPdf(MultipartFile file, String categoryHint) throws IOException {
        long start = System.currentTimeMillis();
        long maxSizeBytes = (long) maxFileSizeMb * 1024 * 1024;
        PdfFileValidator.validate(file, maxSizeBytes, maxPages);

        String batchId = IdGenerator.batchId();
        DocumentImportResult result = new DocumentImportResult();
        result.setBatchId(batchId);

        byte[] pdfBytes = file.getBytes();

        // 嵌入图直取（零渲染损失的原图，优先于 AI 裁剪）；失败不影响主流程。
        // 放在渲染之前执行：避免与整页位图同时占堆，降低内存峰值
        Map<Integer, List<BufferedImage>> embeddedByPage = extractEmbeddedImagesSafely(pdfBytes, batchId);

        List<BufferedImage> pageImages = PdfRenderer.renderPages(pdfBytes, renderDpi);
        result.setTotalPages(pageImages.size());
        log.info("PDF 渲染完成，batchId={}，总页数={}，耗时 {}ms",
            batchId, pageImages.size(), System.currentTimeMillis() - start);

        if (pageImages.isEmpty()) {
            result.setFailedCount(1);
            result.getFailures().add(new DocumentImportFailure(0, "PDF 没有可读取的页面"));
            return result;
        }

        // 分批进行页面区域检测
        List<DocumentProductRegion> allRegions = detectProductRegions(pageImages);
        log.info("PDF 页面区域检测完成，batchId={}，共 {} 页产品页",
            batchId, allRegions.stream().filter(PdfImportService::isProductPageType).count());

        // 逐产品创建录入任务：嵌入图直取优先，AI bbox 精修裁剪兜底
        int productPages = 0;
        int totalProducts = 0;
        int successCount = 0;
        int failedCount = 0;
        for (DocumentProductRegion region : allRegions) {
            // 注意：只按 pageType 判断，AI 判为产品页但漏检 bbox 时也要走嵌入图兜底
            if (!isProductPageType(region)) {
                continue;
            }
            productPages++;
            BufferedImage pageImage = pageImages.get(region.getPageIndex());
            List<ProductSource> sources = buildProductSources(region,
                embeddedByPage.get(region.getPageIndex()), pageImage.getWidth(), pageImage.getHeight());
            if (sources.isEmpty()) {
                log.warn("产品页未提取到任何产品图（AI 漏检且无嵌入大图），batchId={}，pageIndex={}",
                    batchId, region.getPageIndex());
            }
            totalProducts += sources.size();
            for (ProductSource source : sources) {
                try {
                    EntryInfo entryInfo = createEntryFromSource(batchId, pageImage, source, categoryHint);
                    if (entryInfo != null && entryInfo.rspuId != null) {
                        result.getRspuIds().add(entryInfo.rspuId);
                        result.getTaskIds().add(entryInfo.taskId);
                        successCount++;
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.warn("产品图提取或录入失败，batchId={}，pageIndex={}", batchId, region.getPageIndex(), e);
                    result.getFailures().add(new DocumentImportFailure(region.getPageIndex(),
                        "产品录入失败: " + e.getMessage()));
                }
            }
        }
        result.setProductPages(productPages);
        result.setTotalProducts(totalProducts);
        result.setSuccessCount(successCount);
        result.setFailedCount(failedCount);

        log.info("PDF 导入完成，batchId={}，总页数={}，产品页={}，产品数={}，成功={}，失败={}，总耗时 {}ms",
            batchId, result.getTotalPages(), result.getProductPages(), result.getTotalProducts(),
            successCount, failedCount, System.currentTimeMillis() - start);

        return result;
    }

    /**
     * 按 pageType 判断产品页（不要求 products 非空，容忍 AI 漏检 bbox 的情况）。
     */
    private static boolean isProductPageType(DocumentProductRegion region) {
        return "product".equalsIgnoreCase(region.getPageType());
    }

    /**
     * 单个产品的图片来源：嵌入原图（embeddedImage 非空）或页面 bbox 裁剪（bbox 非空）。
     */
    private record ProductSource(String estimatedCategory, ProductBoundingBox bbox, BufferedImage embeddedImage) {
    }

    /**
     * 构建一页的产品来源列表。
     *
     * <p>决策规则：页面含大面积嵌入图且数量不少于 AI 检出的有效产品时，
     * 直接使用嵌入原图（零渲染损失、天然完整）；否则走 AI bbox 裁剪路径
     * （bbox 先经 {@link ProductBoxRefiner} 清洗去重）。</p>
     */
    private List<ProductSource> buildProductSources(DocumentProductRegion region,
                                                    List<BufferedImage> embeddedImages,
                                                    int pageWidth, int pageHeight) {
        List<ProductBoxRefiner.Refined<DocumentProductRegion.PageProduct>> refined =
            ProductBoxRefiner.refineAll(region.getProducts(),
                DocumentProductRegion.PageProduct::getBbox, pageWidth, pageHeight);

        if (embeddedImages != null && !embeddedImages.isEmpty() && embeddedImages.size() >= refined.size()) {
            List<ProductSource> sources = new ArrayList<>(embeddedImages.size());
            for (int i = 0; i < embeddedImages.size(); i++) {
                // 品类按检出顺序映射，嵌入图多于 AI 产品时映射不到则交给 hint 兜底
                String category = i < refined.size() ? refined.get(i).source().getEstimatedCategory() : null;
                sources.add(new ProductSource(category, null, embeddedImages.get(i)));
            }
            return sources;
        }

        List<ProductSource> sources = new ArrayList<>(refined.size());
        for (ProductBoxRefiner.Refined<DocumentProductRegion.PageProduct> r : refined) {
            sources.add(new ProductSource(r.source().getEstimatedCategory(), r.box(), null));
        }
        return sources;
    }

    /**
     * 抽取嵌入大图，失败（含 OOM）时降级为空 Map（纯 AI 裁剪路径）。
     */
    private Map<Integer, List<BufferedImage>> extractEmbeddedImagesSafely(byte[] pdfBytes, String batchId) {
        try {
            Map<Integer, List<BufferedImage>> embedded =
                PdfEmbeddedImageExtractor.extractLargeImages(pdfBytes, embeddedMinAreaRatio, embeddedMinPixelEdge);
            if (!embedded.isEmpty()) {
                log.info("PDF 嵌入图抽取完成，batchId={}，共 {} 页含大嵌入图", batchId, embedded.size());
            }
            return embedded;
        } catch (OutOfMemoryError e) {
            // 防御性兜底：单图解码已有像素上限拦截，理论上不应到达；一旦发生必须让主流程继续
            log.error("PDF 嵌入图抽取内存不足，降级为纯 AI 裁剪路径，batchId={}", batchId);
            return Map.of();
        } catch (Exception e) {
            log.warn("PDF 嵌入图抽取失败，降级为纯 AI 裁剪路径，batchId={}", batchId, e);
            return Map.of();
        }
    }

    /**
     * 分批检测所有页面的产品区域。
     */
    private List<DocumentProductRegion> detectProductRegions(List<BufferedImage> pageImages) {
        List<DocumentProductRegion> allRegions = new ArrayList<>(pageImages.size());
        for (int i = 0; i < pageImages.size(); i++) {
            allRegions.add(null);
        }

        int totalPages = pageImages.size();
        for (int start = 0; start < totalPages; start += detectBatchSize) {
            int end = Math.min(start + detectBatchSize, totalPages);
            List<BufferedImage> batchImages = pageImages.subList(start, end);

            try {
                List<InputStream> compressedStreams = new ArrayList<>(batchImages.size());
                for (BufferedImage image : batchImages) {
                    compressedStreams.add(compressForDetection(image));
                }
                List<DocumentProductRegion> batchRegions = visionService.detectPageRegions(compressedStreams, null);
                for (int i = 0; i < batchRegions.size(); i++) {
                    DocumentProductRegion region = batchRegions.get(i);
                    region.setPageIndex(start + i);
                    allRegions.set(start + i, region);
                }
            } catch (Exception e) {
                log.error("页面区域检测失败，pageRange={}-{}，降级为单页 unknown", start, end - 1, e);
                for (int i = start; i < end; i++) {
                    DocumentProductRegion fallback = new DocumentProductRegion();
                    fallback.setPageIndex(i);
                    fallback.setPageType("unknown");
                    allRegions.set(i, fallback);
                }
            }
        }

        retryUnknownPages(pageImages, allRegions);
        return allRegions;
    }

    /**
     * 对降级为 unknown 的页（批检测失败或 JSON 截断）逐页单独重试一次，
     * 避免整批失败导致产品页整体丢失。
     */
    private void retryUnknownPages(List<BufferedImage> pageImages, List<DocumentProductRegion> allRegions) {
        for (int i = 0; i < allRegions.size(); i++) {
            DocumentProductRegion region = allRegions.get(i);
            if (region == null || !"unknown".equals(region.getPageType())) {
                continue;
            }
            try {
                List<DocumentProductRegion> retried = visionService.detectPageRegions(
                    List.of(compressForDetection(pageImages.get(i))), null);
                if (!retried.isEmpty() && retried.get(0) != null
                    && !"unknown".equals(retried.get(0).getPageType())) {
                    DocumentProductRegion recovered = retried.get(0);
                    recovered.setPageIndex(i);
                    allRegions.set(i, recovered);
                    log.info("unknown 页单页重试成功，pageIndex={}，pageType={}", i, recovered.getPageType());
                }
            } catch (Exception e) {
                log.warn("unknown 页单页重试失败，保持 unknown，pageIndex={}", i, e);
            }
        }
    }

    /**
     * 将页面图压缩为适合 AI 检测的大小。
     */
    private InputStream compressForDetection(BufferedImage source) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        if (Math.max(width, height) <= DETECT_IMAGE_MAX_EDGE) {
            return encodeJpeg(source);
        }

        double ratio = (double) DETECT_IMAGE_MAX_EDGE / Math.max(width, height);
        int newWidth = (int) Math.round(width * ratio);
        int newHeight = (int) Math.round(height * ratio);

        Image scaled = source.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
        BufferedImage output = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return encodeJpeg(output);
    }

    private InputStream encodeJpeg(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new ByteArrayInputStream(out.toByteArray());
    }

    /**
     * 提取产品图（嵌入原图直取 或 bbox 外扩裁剪 + 白边精修）并创建录入任务。
     *
     * @return 录入信息，包含 RSPU ID 和任务 ID
     */
    private EntryInfo createEntryFromSource(String batchId, BufferedImage pageImage, ProductSource source,
                                            String categoryHint) throws IOException {
        byte[] imageBytes;
        if (source.embeddedImage() != null) {
            // 嵌入原图：整图白边精修（去扫描边距 + 留白），不经任何渲染缩放
            imageBytes = ImageWhitespaceTrimmer.cropRefineToJpeg(source.embeddedImage(),
                new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, CROP_PAD_RATIO, outputQuality);
        } else {
            // AI bbox：外扩防切边 + 白边收紧 + 留白
            imageBytes = ImageWhitespaceTrimmer.cropRefineToJpeg(pageImage, source.bbox(),
                CROP_EXPAND_RATIO, CROP_PAD_RATIO, outputQuality);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("提取产品图失败");
        }

        String effectiveCategory = resolveCategory(source.estimatedCategory(), categoryHint);
        String filename = batchId + "_page_product.jpg";
        Map<String, Object> entryResult;
        try (InputStream in = new ByteArrayInputStream(imageBytes)) {
            entryResult = productService.createEntryFromStream(in, filename, imageBytes.length, effectiveCategory);
        }

        Object rspuId = entryResult.get("rspuId");
        Object taskId = entryResult.get("taskId");
        if (rspuId != null && taskId != null) {
            return new EntryInfo(rspuId.toString(), taskId.toString());
        }
        return null;
    }

    private record EntryInfo(String rspuId, String taskId) {
    }

    /**
     * 解析最终品类码：优先使用 AI 检测出的品类，未检测出时使用用户提示，最后兜底 FS。
     */
    private String resolveCategory(String detectedCategory, String categoryHint) {
        if (detectedCategory != null && !detectedCategory.isBlank()) {
            return detectedCategory.trim().toUpperCase();
        }
        if (categoryHint != null && !categoryHint.isBlank()) {
            return categoryHint.trim().toUpperCase();
        }
        return "FS";
    }
}
