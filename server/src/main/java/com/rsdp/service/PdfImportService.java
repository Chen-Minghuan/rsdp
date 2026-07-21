package com.rsdp.service;

import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.dto.response.DocumentImportFailure;
import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.exception.BusinessException;
import com.rsdp.util.ImageCropper;
import com.rsdp.util.PdfFileValidator;
import com.rsdp.util.PdfRenderer;
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

    @Value("${rsdp.document-import.pdf.render-dpi:150}")
    private float renderDpi;

    @Value("${rsdp.document-import.pdf.detect-batch-size:20}")
    private int detectBatchSize;

    @Value("${rsdp.document-import.pdf.output-quality:0.9}")
    private float outputQuality;

    private static final int DETECT_IMAGE_MAX_EDGE = 1024;

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

        List<BufferedImage> pageImages;
        try (InputStream in = file.getInputStream()) {
            pageImages = PdfRenderer.renderPages(in, renderDpi);
        }
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
            batchId, allRegions.stream().filter(DocumentProductRegion::isProductPage).count());

        int productPages = 0;
        int totalProducts = 0;
        for (DocumentProductRegion region : allRegions) {
            if (region.isProductPage()) {
                productPages++;
                totalProducts += region.getProducts().size();
            }
        }
        result.setProductPages(productPages);
        result.setTotalProducts(totalProducts);

        // 裁剪并逐产品创建录入任务
        int successCount = 0;
        int failedCount = 0;
        for (DocumentProductRegion region : allRegions) {
            if (!region.isProductPage()) {
                continue;
            }
            BufferedImage pageImage = pageImages.get(region.getPageIndex());
            for (DocumentProductRegion.PageProduct product : region.getProducts()) {
                try {
                    String effectiveCategory = resolveCategory(product.getEstimatedCategory(), categoryHint);
                    EntryInfo entryInfo = createEntryFromCrop(batchId, pageImage, product.getBbox(), effectiveCategory);
                    if (entryInfo != null && entryInfo.rspuId != null) {
                        result.getRspuIds().add(entryInfo.rspuId);
                        result.getTaskIds().add(entryInfo.taskId);
                        successCount++;
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.warn("裁剪或录入产品失败，batchId={}，pageIndex={}", batchId, region.getPageIndex(), e);
                    result.getFailures().add(new DocumentImportFailure(region.getPageIndex(),
                        "产品录入失败: " + e.getMessage()));
                }
            }
        }

        result.setSuccessCount(successCount);
        result.setFailedCount(failedCount);

        log.info("PDF 导入完成，batchId={}，总页数={}，产品页={}，产品数={}，成功={}，失败={}，总耗时 {}ms",
            batchId, result.getTotalPages(), result.getProductPages(), result.getTotalProducts(),
            successCount, failedCount, System.currentTimeMillis() - start);

        return result;
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

        return allRegions;
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
     * 根据 bbox 裁剪产品图并创建录入任务。
     *
     * @return 录入信息，包含 RSPU ID 和任务 ID
     */
    private EntryInfo createEntryFromCrop(String batchId, BufferedImage pageImage, ProductBoundingBox bbox,
                                          String categoryCode) throws IOException {
        byte[] croppedBytes = ImageCropper.cropToJpeg(pageImage, bbox, outputQuality);
        if (croppedBytes == null || croppedBytes.length == 0) {
            throw new BusinessException("裁剪产品图失败");
        }

        String filename = batchId + "_page_product.jpg";
        Map<String, Object> entryResult;
        try (InputStream in = new ByteArrayInputStream(croppedBytes)) {
            entryResult = productService.createEntryFromStream(in, filename, croppedBytes.length, categoryCode);
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
