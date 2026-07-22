package com.rsdp.service;

import com.rsdp.dto.SceneDetectedProduct;
import com.rsdp.dto.response.SceneImportResult;
import com.rsdp.exception.BusinessException;
import com.rsdp.util.IdGenerator;
import com.rsdp.util.ImageCropper;
import com.rsdp.util.ImageUploadValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 场景图拆分录入服务：一张室内场景照片 → AI 检测家具单品 → 逐件裁剪并创建 RSPU 录入任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneImportService {

    private final VisionService visionService;
    private final ProductService productService;
    private final ImageUploadValidator imageUploadValidator;

    @Value("${rsdp.scene-import.max-file-size-mb:10}")
    private int maxFileSizeMb;

    @Value("${rsdp.scene-import.max-products:12}")
    private int maxProducts;

    @Value("${rsdp.scene-import.output-quality:0.9}")
    private float outputQuality;

    @Value("${rsdp.scene-import.refine-enabled:true}")
    private boolean refineEnabled;

    private static final int DETECT_IMAGE_MAX_EDGE = 1024;

    /**
     * 导入场景照片：AI 检测家具单品，逐件裁剪并创建 RSPU 录入任务（每件独立异步 AI 识别）。
     *
     * @param file         场景照片
     * @param categoryHint 品类提示，可为空（AI 检测品类优先，其次提示，兜底 FS）
     * @return 批次结果（含逐件明细）
     * @throws IOException 文件处理失败
     */
    public SceneImportResult importScene(MultipartFile file, String categoryHint) throws IOException {
        long start = System.currentTimeMillis();
        imageUploadValidator.validate(file, (long) maxFileSizeMb * 1024 * 1024);

        String batchId = IdGenerator.batchId();
        SceneImportResult result = new SceneImportResult();
        result.setBatchId(batchId);

        BufferedImage sceneImage;
        try (InputStream in = file.getInputStream()) {
            sceneImage = ImageIO.read(in);
        }
        if (sceneImage == null) {
            throw new BusinessException("无法解析场景图片");
        }

        List<SceneDetectedProduct> detected;
        try (InputStream detectStream = compressForDetection(sceneImage)) {
            detected = visionService.detectSceneProducts(detectStream, maxProducts);
        }
        detected = filterDetectedProducts(detected);
        if (detected.size() > maxProducts) {
            detected = detected.subList(0, maxProducts);
        }
        result.setTotalProducts(detected.size());
        log.info("场景家具检测完成，batchId={}，检测到 {} 件，耗时 {}ms",
            batchId, detected.size(), System.currentTimeMillis() - start);

        for (SceneDetectedProduct product : detected) {
            SceneImportResult.SceneImportProduct item = new SceneImportResult.SceneImportProduct();
            item.setBbox(product.getBbox());
            item.setLabel(product.getLabel());
            item.setCategoryCode(resolveCategory(product.getEstimatedCategory(), categoryHint));
            result.getProducts().add(item);
            try {
                byte[] coarseBytes = ImageCropper.cropToJpeg(sceneImage, product.getBbox(), outputQuality);
                if (coarseBytes == null || coarseBytes.length == 0) {
                    throw new BusinessException("裁剪产品图失败");
                }

                // 方案 B：粗裁剪图二次 AI 精修 bbox，失败/无主体时回退粗框
                byte[] croppedBytes = coarseBytes;
                if (refineEnabled) {
                    croppedBytes = refineCrop(sceneImage, product, coarseBytes, item);
                }

                Map<String, Object> entryResult;
                try (InputStream cropIn = new ByteArrayInputStream(croppedBytes)) {
                    entryResult = productService.createEntryFromStream(
                        cropIn, batchId + "_scene_product.jpg", croppedBytes.length, item.getCategoryCode());
                }
                item.setRspuId(String.valueOf(entryResult.get("rspuId")));
                item.setTaskId(String.valueOf(entryResult.get("taskId")));
                Object imageIds = entryResult.get("imageIds");
                if (imageIds instanceof List<?> list && !list.isEmpty()) {
                    item.setImageId(String.valueOf(list.get(0)));
                }
                item.setStatus("success");
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                log.warn("场景产品录入失败，batchId={}，bbox={}", batchId, product.getBbox(), e);
                item.setStatus("failed");
                item.setError(e.getMessage());
                result.setFailedCount(result.getFailedCount() + 1);
            }
        }

        log.info("场景图导入完成，batchId={}，检测={}，成功={}，失败={}，总耗时 {}ms",
            batchId, result.getTotalProducts(), result.getSuccessCount(),
            result.getFailedCount(), System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 将场景图压缩为适合 AI 检测的大小（长边 ≤1024，JPEG）。
     */
    private InputStream compressForDetection(BufferedImage source) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage output = source;
        if (Math.max(width, height) > DETECT_IMAGE_MAX_EDGE) {
            double ratio = (double) DETECT_IMAGE_MAX_EDGE / Math.max(width, height);
            int newWidth = (int) Math.round(width * ratio);
            int newHeight = (int) Math.round(height * ratio);
            Image scaled = source.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            output = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = output.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(output, "jpg", out);
        return new ByteArrayInputStream(out.toByteArray());
    }

    /**
     * 过滤 AI 检测出的问题框：
     * 面积过小（&lt;3%，噪声）或过大（&gt;70%，接近整图，裁剪无意义）直接丢弃；
     * 两框重叠面积占较小框比例 &gt; 30% 视为重复/包含，保留较小（更紧贴）的框。
     */
    private List<SceneDetectedProduct> filterDetectedProducts(List<SceneDetectedProduct> detected) {
        List<SceneDetectedProduct> areaFiltered = new java.util.ArrayList<>();
        for (SceneDetectedProduct p : detected) {
            double area = p.getBbox().getWidth() * p.getBbox().getHeight();
            if (area < MIN_BBOX_AREA) {
                log.warn("丢弃面积过小的检测框（{}）：{}", String.format("%.3f", area), p.getLabel());
            } else if (area > MAX_BBOX_AREA) {
                log.warn("丢弃面积过大的检测框（{}）：{}", String.format("%.3f", area), p.getLabel());
            } else {
                areaFiltered.add(p);
            }
        }

        List<SceneDetectedProduct> result = new java.util.ArrayList<>();
        for (int i = 0; i < areaFiltered.size(); i++) {
            SceneDetectedProduct current = areaFiltered.get(i);
            boolean dominated = false;
            for (int j = 0; j < areaFiltered.size(); j++) {
                if (i == j) {
                    continue;
                }
                SceneDetectedProduct other = areaFiltered.get(j);
                if (overlapRatioWithSmaller(current.getBbox(), other.getBbox()) > MAX_BBOX_OVERLAP
                    && areaOf(other) < areaOf(current)) {
                    dominated = true;
                    log.warn("丢弃与更紧贴框重叠的检测框：{}（保留 {}）", current.getLabel(), other.getLabel());
                    break;
                }
            }
            if (!dominated) {
                result.add(current);
            }
        }
        return result;
    }

    private double areaOf(SceneDetectedProduct p) {
        return p.getBbox().getWidth() * p.getBbox().getHeight();
    }

    /**
     * 计算两个比例坐标 bbox 的重叠度：交集面积 / 较小框面积（包含场景下为 1.0）。
     */
    private double overlapRatioWithSmaller(com.rsdp.dto.ProductBoundingBox a, com.rsdp.dto.ProductBoundingBox b) {
        double interX = Math.max(a.getX(), b.getX());
        double interY = Math.max(a.getY(), b.getY());
        double interW = Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth()) - interX;
        double interH = Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight()) - interY;
        if (interW <= 0 || interH <= 0) {
            return 0.0;
        }
        double inter = interW * interH;
        double smaller = Math.min(a.getWidth() * a.getHeight(), b.getWidth() * b.getHeight());
        return smaller <= 0 ? 0.0 : inter / smaller;
    }

    /**
     * 对粗裁剪图做二次 AI 精修：成功时用精修框重新从原图裁剪（坐标由相对粗框映射回原图），
     * 精修失败或判定无单一家具主体时回退使用粗裁剪。
     * 精修成功的品类/名称优先于初检结果。
     */
    private byte[] refineCrop(BufferedImage sceneImage, SceneDetectedProduct product,
                              byte[] coarseBytes, SceneImportResult.SceneImportProduct item) throws IOException {
        SceneDetectedProduct refined;
        try (InputStream refineIn = new ByteArrayInputStream(coarseBytes)) {
            refined = visionService.refineSceneProduct(refineIn);
        } catch (Exception e) {
            log.warn("产品精修失败，使用粗框：{}", product.getLabel(), e);
            return coarseBytes;
        }
        if (refined == null) {
            return coarseBytes;
        }

        com.rsdp.dto.ProductBoundingBox absolute = toAbsoluteBbox(product.getBbox(), refined.getBbox());
        if (!absolute.isValid()) {
            log.warn("精修 bbox 映射后非法，使用粗框：{}", refined.getBbox());
            return coarseBytes;
        }
        if (StringUtils.hasText(refined.getEstimatedCategory())) {
            item.setCategoryCode(refined.getEstimatedCategory().trim().toUpperCase());
        }
        if (StringUtils.hasText(refined.getLabel())) {
            item.setLabel(refined.getLabel());
        }
        byte[] refinedBytes = ImageCropper.cropToJpeg(sceneImage, absolute, outputQuality);
        if (refinedBytes == null || refinedBytes.length == 0) {
            log.warn("精修框裁剪失败，使用粗框");
            return coarseBytes;
        }
        log.info("产品精修成功：{}，粗框 {} → 精修框 {}", product.getLabel(), product.getBbox(), absolute);
        return refinedBytes;
    }

    /**
     * 将「相对粗裁剪图」的比例坐标映射回原图比例坐标（含边界收敛）。
     */
    private com.rsdp.dto.ProductBoundingBox toAbsoluteBbox(com.rsdp.dto.ProductBoundingBox coarse,
                                                           com.rsdp.dto.ProductBoundingBox relative) {
        double x = clamp01(coarse.getX() + relative.getX() * coarse.getWidth());
        double y = clamp01(coarse.getY() + relative.getY() * coarse.getHeight());
        double w = clamp01(relative.getWidth() * coarse.getWidth());
        double h = clamp01(relative.getHeight() * coarse.getHeight());
        if (x + w > 1.0) {
            w = 1.0 - x;
        }
        if (y + h > 1.0) {
            h = 1.0 - y;
        }
        return new com.rsdp.dto.ProductBoundingBox(x, y, w, h);
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static final double MIN_BBOX_AREA = 0.03;
    private static final double MAX_BBOX_AREA = 0.70;
    private static final double MAX_BBOX_OVERLAP = 0.3;

    /**
     * 解析最终品类码：AI 检测品类优先，其次用户提示，最后兜底 FS。
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
