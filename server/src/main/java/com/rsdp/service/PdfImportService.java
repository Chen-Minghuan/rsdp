package com.rsdp.service;

import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.dto.response.DocumentImportFailure;
import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.exception.BusinessException;
import com.rsdp.util.ImageBackgroundAnalyzer;
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
     * 2688px 相比 1568px 能显著提升大幅面页面（4000px+）上
     * 白色/浅色产品（白底低对比）的 bbox 边界精度，代价是检测耗时与流量增加。
     */
    private static final int DETECT_IMAGE_MAX_EDGE = 2688;

    /**
     * 裁剪前 bbox 外扩比例（相对 bbox 自身宽高）。
     *
     * <p>注意：历史上该值是相对整页宽高的 0.03（200DPI A4 约 74~105px），
     * 对小产品框过度外扩、容易把旁边说明文字框进来；改为相对 bbox 后
     * 小框少扩、大框多扩。收紧/文字带重裁已锚定核心框（不切入 AI 原始框），
     * 外扩到 10% 以更好容忍 AI 框低估（浅色/深色产品底部被框小），
     * 多出来的边距由收紧和文字带重裁清掉。</p>
     */
    private static final double BOX_EXPAND_RATIO = 0.10;

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
     * nearbyText 为页面级检测时提取的产品旁说明文字，随录入任务传递，作为裁剪图 OCR 的补充。
     * siblingCores 为同页其他产品的核心框，裁切边外延恢复时用于限制外延不侵占兄弟产品区域。
     */
    private record ProductSource(String estimatedCategory, ProductBoundingBox bbox, BufferedImage embeddedImage,
                                 OcrResult nearbyText, List<ProductBoundingBox> siblingCores) {
    }

    /**
     * 构建一页的产品来源列表。
     *
     * <p>决策规则：页面含大面积嵌入图且数量不少于 AI 检出的有效产品时，
     * 直接使用嵌入原图（零渲染损失、天然完整）；否则走 AI bbox 裁剪路径
     * （bbox 先经 {@link ProductBoxRefiner} 清洗去重）。</p>
     *
     * <p>场景图（效果图）甄别：AI 标记 imageKind=scene 且无产品说明文字（nearbyText 为空）
     * 的框视为场景中的点缀产品，直接剔除；场景中完整可见且带说明文字的产品保留，走 bbox
     * 裁剪录入。嵌入图经 {@link ImageBackgroundAnalyzer} 边框带规则判别，疑似场景图不直取
     * （场景照片不是干净的单品主图），由 AI bbox 路径按上述规则处理。</p>
     */
    private List<ProductSource> buildProductSources(DocumentProductRegion region,
                                                    List<BufferedImage> embeddedImages,
                                                    int pageWidth, int pageHeight) {
        List<ProductBoxRefiner.Refined<DocumentProductRegion.PageProduct>> refined = new ArrayList<>(
            ProductBoxRefiner.refineAll(region.getProducts(),
                DocumentProductRegion.PageProduct::getBbox, pageWidth, pageHeight));
        // 剔除"场景中且无文字说明"的产品框；场景中完整且带说明文字的产品保留录入
        int sceneBoxCount = 0;
        for (int i = refined.size() - 1; i >= 0; i--) {
            DocumentProductRegion.PageProduct product = refined.get(i).source();
            if (isSceneImage(product) && !hasProductText(product.getNearbyText())) {
                refined.remove(i);
                sceneBoxCount++;
            }
        }
        if (sceneBoxCount > 0) {
            log.info("剔除场景中无说明文字的产品框 {} 个，pageIndex={}", sceneBoxCount, region.getPageIndex());
        }

        // 疑似场景图的嵌入图不直取（场景照片不是干净的单品主图）：
        // 场景中完整且带说明文字的产品由上方保留的 AI bbox 框裁剪录入
        List<BufferedImage> standaloneEmbedded = null;
        if (embeddedImages != null && !embeddedImages.isEmpty()) {
            standaloneEmbedded = new ArrayList<>(embeddedImages.size());
            for (BufferedImage embedded : embeddedImages) {
                if (ImageBackgroundAnalyzer.looksLikeSceneImage(embedded)) {
                    log.info("疑似场景嵌入图不直取，交 AI bbox 路径处理，pageIndex={}", region.getPageIndex());
                } else {
                    standaloneEmbedded.add(embedded);
                }
            }
        }

        if (standaloneEmbedded != null && !standaloneEmbedded.isEmpty()
            && standaloneEmbedded.size() >= refined.size()) {
            List<ProductSource> sources = new ArrayList<>(standaloneEmbedded.size());
            for (int i = 0; i < standaloneEmbedded.size(); i++) {
                // 品类与文字按检出顺序映射，嵌入图多于 AI 产品时映射不到则交给 hint 兜底
                String category = i < refined.size() ? refined.get(i).source().getEstimatedCategory() : null;
                OcrResult nearbyText = i < refined.size() ? refined.get(i).source().getNearbyText() : null;
                sources.add(new ProductSource(category, null, standaloneEmbedded.get(i), nearbyText, List.of()));
            }
            return sources;
        }

        List<ProductSource> sources = new ArrayList<>(refined.size());
        for (int i = 0; i < refined.size(); i++) {
            ProductBoxRefiner.Refined<DocumentProductRegion.PageProduct> r = refined.get(i);
            // 同页其他产品的核心框（外延恢复时不侵占这些区域）
            List<ProductBoundingBox> siblings = new ArrayList<>(refined.size() - 1);
            for (int j = 0; j < refined.size(); j++) {
                if (j != i) {
                    siblings.add(refined.get(j).box());
                }
            }
            sources.add(new ProductSource(r.source().getEstimatedCategory(), r.box(), null,
                r.source().getNearbyText(), siblings));
        }
        return sources;
    }

    /**
     * AI 是否把该产品图标记为场景图（imageKind=scene）。为 null 按单品图处理（兼容旧结果）。
     */
    private static boolean isSceneImage(DocumentProductRegion.PageProduct product) {
        return "scene".equalsIgnoreCase(product.getImageKind());
    }

    /**
     * 产品旁是否提取到任何说明文字（品名/型号/尺寸/价格/材质/原文任一项非空）。
     * 场景图中的产品只有带说明文字时才值得建档——文字是"该场景产品在画册中正式售卖"的信号。
     */
    private static boolean hasProductText(OcrResult nearbyText) {
        if (nearbyText == null) {
            return false;
        }
        return isNotBlank(nearbyText.getProductName())
            || isNotBlank(nearbyText.getModelNumber())
            || isNotBlank(nearbyText.getDimensionText())
            || isNotBlank(nearbyText.getPriceText())
            || isNotBlank(nearbyText.getMaterialDescription())
            || isNotBlank(nearbyText.getRawText());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
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

        retryFailedPages(pageImages, allRegions);
        return allRegions;
    }

    /**
     * 对检测失败的页逐页单独重试一次，避免整批失败或模型输出格式抖动导致产品整体丢失：
     * <ul>
     *   <li>unknown 页（批检测失败或 JSON 截断降级）；</li>
     *   <li>pageType=product 但所有产品 bbox 解析为 null 的页（模型偶发不输出位置框，
     *   实测整批 5 页集体出现，单页重试通常能恢复正常输出）。</li>
     * </ul>
     */
    private void retryFailedPages(List<BufferedImage> pageImages, List<DocumentProductRegion> allRegions) {
        for (int i = 0; i < allRegions.size(); i++) {
            DocumentProductRegion region = allRegions.get(i);
            if (region == null || !needsRetry(region)) {
                continue;
            }
            try {
                List<DocumentProductRegion> retried = visionService.detectPageRegions(
                    List.of(compressForDetection(pageImages.get(i))), null);
                if (!retried.isEmpty() && retried.get(0) != null && !needsRetry(retried.get(0))) {
                    DocumentProductRegion recovered = retried.get(0);
                    recovered.setPageIndex(i);
                    allRegions.set(i, recovered);
                    log.info("检测失败页单页重试成功，pageIndex={}，pageType={}", i, recovered.getPageType());
                } else {
                    log.warn("检测失败页单页重试仍无效，pageIndex={}", i);
                }
            } catch (Exception e) {
                log.warn("检测失败页单页重试失败，pageIndex={}", i, e);
            }
        }
    }

    /**
     * 判断该页检测结果是否需要单页重试：unknown 页，或产品页但产品 bbox 全为 null。
     */
    private static boolean needsRetry(DocumentProductRegion region) {
        if ("unknown".equals(region.getPageType())) {
            return true;
        }
        return "product".equalsIgnoreCase(region.getPageType())
            && region.getProducts() != null && !region.getProducts().isEmpty()
            && region.getProducts().stream().allMatch(p -> p.getBbox() == null);
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
     * <p>统一使用文档导入专用的收紧策略（{@link ImageWhitespaceTrimmer.TrimOptions#document()}）：
     * 保守收紧（每边限幅 + 细腿保护 + 场景背景不收）+ 内容重裁（纯色背景下裁掉卡片底部
     * 说明文字带与边缘相邻图切片），宁可多留边也绝不切到产品。</p>
     *
     * @return 录入信息，包含 RSPU ID 和任务 ID
     */
    private EntryInfo createEntryFromSource(String batchId, BufferedImage pageImage, ProductSource source,
                                            String categoryHint) throws IOException {
        byte[] imageBytes;
        if (source.embeddedImage() != null) {
            // 嵌入原图：整图白边精修（去扫描边距 + 留白），不经任何渲染缩放；
            // 无 AI 框可锚定，用保守收紧、不开内容重裁（防浅色产品底部被误吃）
            imageBytes = ImageWhitespaceTrimmer.cropRefineToJpeg(source.embeddedImage(),
                new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, CROP_PAD_RATIO, outputQuality,
                ImageWhitespaceTrimmer.TrimOptions.conservative());
        } else {
            // AI bbox：先做裁切边外延恢复（框底/侧边低估时，把框外连续的产品内容并回核心框），
            // 再按恢复后的核心框外扩裁剪；锚定核心框收紧白边 + 文字带重裁（只清核心框外）+ 留白
            ProductBoundingBox core = recoverCutEdges(pageImage, source.bbox(), source.siblingCores());
            ProductBoundingBox expanded = expandBox(core, BOX_EXPAND_RATIO);
            imageBytes = ImageWhitespaceTrimmer.cropRefineToJpeg(pageImage, expanded,
                0.0, CROP_PAD_RATIO, outputQuality,
                ImageWhitespaceTrimmer.TrimOptions.document(), core);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("提取产品图失败");
        }

        String effectiveCategory = resolveCategory(source.estimatedCategory(), categoryHint);
        String filename = batchId + "_page_product.jpg";
        Map<String, Object> entryResult;
        try (InputStream in = new ByteArrayInputStream(imageBytes)) {
            entryResult = productService.createEntryFromStream(in, filename, imageBytes.length, effectiveCategory,
                source.nearbyText());
        }

        Object rspuId = entryResult.get("rspuId");
        Object taskId = entryResult.get("taskId");
        if (rspuId != null && taskId != null) {
            return new EntryInfo(rspuId.toString(), taskId.toString());
        }
        return null;
    }

    /**
     * 按 bbox 自身宽高的比例外扩（相对坐标，结果钳制在 [0,1] 内）。
     */
    static ProductBoundingBox expandBox(ProductBoundingBox box, double ratio) {
        double dx = box.getWidth() * ratio;
        double dy = box.getHeight() * ratio;
        double x = Math.max(0.0, box.getX() - dx);
        double y = Math.max(0.0, box.getY() - dy);
        double width = Math.min(1.0 - x, box.getWidth() + 2 * dx);
        double height = Math.min(1.0 - y, box.getHeight() + 2 * dy);
        return new ProductBoundingBox(x, y, width, height);
    }

    /**
     * 外延恢复最大比例（相对核心框该方向尺寸）。
     */
    private static final double RECOVER_MAX_RATIO = 0.25;

    /**
     * 外延扫描时判定内容终止的连续空白行/列数。
     */
    private static final int RECOVER_BLANK_RUN = 3;

    /**
     * 裁切边外延恢复：AI 框低估（框边切进产品）时，把框外连续的产品内容并回核心框。
     *
     * <p>白色/浅色产品在白底上最容易被框小（田吉/条纹布艺/像素/波西米亚实测案例）。
     * 对四个方向分别处理：核心框边缘行/列含产品内容（色差或织物纹理，即有"被切断"
     * 信号）时向外逐行/列扫描，内容连续就延伸；遇到 {@value #RECOVER_BLANK_RUN} 个连续
     * 空白行/列（说明文字前的隔断）、兄弟产品框边界、页边或
     * {@value #RECOVER_MAX_RATIO} 上限即停止。恢复后的区域并入核心框——锚定机制会自动
     * 保护它不被收紧/文字带重裁误吃，隔断外的说明文字仍会被正常清掉。</p>
     *
     * @param page     渲染页面图
     * @param core     AI 原始框（相对坐标）
     * @param siblings 同页其他产品的核心框
     * @return 恢复后的核心框；无切断信号时原样返回
     */
    static ProductBoundingBox recoverCutEdges(BufferedImage page, ProductBoundingBox core,
                                              List<ProductBoundingBox> siblings) {
        if (page == null || core == null || !core.isValid()) {
            return core;
        }
        int bgLum = pageBackgroundLuminance(page);
        double x0 = core.getX();
        double y0 = core.getY();
        double x1 = x0 + core.getWidth();
        double y1 = y0 + core.getHeight();
        List<ProductBoundingBox> safeSiblings = siblings != null ? siblings : List.of();

        double newY1 = recoverEdge(page, safeSiblings, bgLum, true, 1, x0, x1, y1, core.getHeight());
        double newX1 = recoverEdge(page, safeSiblings, bgLum, false, 1, y0, y1, x1, core.getWidth());
        double newY0 = recoverEdge(page, safeSiblings, bgLum, true, -1, x0, x1, y0, core.getHeight());
        double newX0 = recoverEdge(page, safeSiblings, bgLum, false, -1, y0, y1, x0, core.getWidth());

        if (newX0 == x0 && newY0 == y0 && newX1 == x1 && newY1 == y1) {
            return core;
        }
        double nx0 = Math.max(0.0, Math.min(newX0, newX1));
        double ny0 = Math.max(0.0, Math.min(newY0, newY1));
        double nx1 = Math.min(1.0, Math.max(newX0, newX1));
        double ny1 = Math.min(1.0, Math.max(newY0, newY1));
        log.info("裁切边外延恢复：底边 {}→{}，右边 {}→{}，顶边 {}→{}，左边 {}→{}",
            y1, newY1, x1, newX1, y0, newY0, x0, newX0);
        return new ProductBoundingBox(nx0, ny0, nx1 - nx0, ny1 - ny0);
    }

    /**
     * 单方向外延恢复，返回该方向新的边缘坐标（相对值）。
     *
     * @param isRow        true=垂直方向（上/下，扫行），false=水平方向（左/右，扫列）
     * @param sign         +1=下/右边缘，-1=上/左边缘
     * @param rangeFrom    扫描跨度的起始（相对坐标：垂直方向为 x0，水平方向为 y0）
     * @param rangeTo      扫描跨度的结束（相对坐标）
     * @param edgeRel      核心框该方向的边缘（相对坐标）
     * @param coreSizeRel  核心框该方向的尺寸（相对坐标，用于外延上限）
     */
    private static double recoverEdge(BufferedImage page, List<ProductBoundingBox> siblings, int bgLum,
                                      boolean isRow, int sign, double rangeFrom, double rangeTo,
                                      double edgeRel, double coreSizeRel) {
        int pageW = page.getWidth();
        int pageH = page.getHeight();
        int span = isRow ? pageH : pageW;
        int cross = isRow ? pageW : pageH;
        int edgePx = sign > 0 ? (int) Math.round(edgeRel * span) - 1 : (int) Math.round(edgeRel * span);
        if (edgePx < 0 || edgePx >= span) {
            return edgeRel;
        }
        int fromPx = (int) Math.round(rangeFrom * cross);
        int toPx = Math.min(cross, (int) Math.round(rangeTo * cross));

        // 切断信号：边缘行/列本身是产品内容；边缘是背景说明框外没有产品延续，不外延
        if (!lineHasContent(page, isRow, edgePx, fromPx, toPx, bgLum)) {
            return edgeRel;
        }

        // 外延上限：页边 → 兄弟框边界 → 核心框尺寸比例上限，逐层收紧
        int limit = sign > 0 ? span - 1 : 0;
        for (ProductBoundingBox sibling : siblings) {
            double otherFrom = isRow ? sibling.getX() : sibling.getY();
            double otherTo = otherFrom + (isRow ? sibling.getWidth() : sibling.getHeight());
            if (!rangesOverlap(rangeFrom, rangeTo, otherFrom, otherTo)) {
                continue;
            }
            double siblingEdgeRel = sign > 0 ? (isRow ? sibling.getY() : sibling.getX())
                : (isRow ? sibling.getY() + sibling.getHeight() : sibling.getX() + sibling.getWidth());
            int siblingEdgePx = (int) Math.round(siblingEdgeRel * span) + (sign > 0 ? -2 : 2);
            if (sign > 0 && siblingEdgePx > edgePx) {
                limit = Math.min(limit, siblingEdgePx);
            }
            if (sign < 0 && siblingEdgePx < edgePx) {
                limit = Math.max(limit, siblingEdgePx);
            }
        }
        int cap = (int) Math.round(edgeRel * span + sign * coreSizeRel * span * RECOVER_MAX_RATIO);
        limit = sign > 0 ? Math.min(limit, cap) : Math.max(limit, cap);

        int blankRun = 0;
        int contentEnd = edgePx;
        for (int p = edgePx + sign; sign > 0 ? p <= limit : p >= limit; p += sign) {
            if (lineHasContent(page, isRow, p, fromPx, toPx, bgLum)) {
                contentEnd = p;
                blankRun = 0;
            } else if (++blankRun >= RECOVER_BLANK_RUN) {
                break;
            }
        }
        if (contentEnd == edgePx) {
            return edgeRel;
        }
        return (double) (contentEnd + (sign > 0 ? 1 : 0)) / span;
    }

    /**
     * 行/列是否含产品内容：与背景亮度差达标的像素占比 ≥30%（普通产品/阴影），
     * 或亮度标准差 ≥5（浅色织物的纹理/明暗起伏，白底上几乎纯白的平滑区域不达标）。
     */
    private static boolean lineHasContent(BufferedImage page, boolean isRow, int index,
                                          int fromPx, int toPx, int bgLum) {
        int n = toPx - fromPx;
        if (n <= 0 || index < 0 || index >= (isRow ? page.getHeight() : page.getWidth())) {
            return false;
        }
        int step = Math.max(1, n / 400);
        int count = 0;
        int diffCount = 0;
        double sum = 0;
        double sumSq = 0;
        for (int i = fromPx; i < toPx; i += step) {
            int lum = luminance(isRow ? page.getRGB(i, index) : page.getRGB(index, i));
            count++;
            sum += lum;
            sumSq += (double) lum * lum;
            if (Math.abs(lum - bgLum) > 12) {
                diffCount++;
            }
        }
        double mean = sum / count;
        double stddev = Math.sqrt(Math.max(0.0, sumSq / count - mean * mean));
        return (double) diffCount / count >= 0.3 || stddev >= 5.0;
    }

    private static boolean rangesOverlap(double a0, double a1, double b0, double b1) {
        return a0 < b1 && b0 < a1;
    }

    /**
     * 页面背景亮度：四角 5×5 采样亮度的中位数。
     */
    private static int pageBackgroundLuminance(BufferedImage page) {
        int width = page.getWidth();
        int height = page.getHeight();
        int sample = Math.min(5, Math.min(width, height));
        int[][] corners = {{0, 0}, {width - sample, 0}, {0, height - sample}, {width - sample, height - sample}};
        int[] means = new int[4];
        for (int i = 0; i < corners.length; i++) {
            long sum = 0;
            for (int dy = 0; dy < sample; dy++) {
                for (int dx = 0; dx < sample; dx++) {
                    sum += luminance(page.getRGB(corners[i][0] + dx, corners[i][1] + dy));
                }
            }
            means[i] = (int) (sum / (sample * sample));
        }
        java.util.Arrays.sort(means);
        return (means[1] + means[2]) / 2;
    }

    /**
     * 像素亮度（Rec.601）。
     */
    private static int luminance(int rgb) {
        return (299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
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
