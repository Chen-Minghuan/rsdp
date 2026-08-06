package com.rsdp.service;

import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.entity.ImageAssets;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.IdGenerator;
import com.rsdp.util.ImageWhitespaceTrimmer;
import com.rsdp.util.ProductBoxRefiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 产品主图智能裁剪服务。
 *
 * <p>录入图片常包含搭配品、装饰品、场景背景，直接作为主图观感差。
 * 本服务通过 AI 检测图片中最主要的家具产品 bbox，裁剪并白边精修后输出。</p>
 *
 * <p>安全策略：检测失败、无有效框、面积越界或任何异常都返回 {@link Optional#empty()}，
 * 由调用方回退原图，绝不阻断录入主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSubjectCropService {

    private final VisionService visionService;
    private final StorageService storageService;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AuditLogService auditLogService;

    @Value("${rsdp.ai.subject-crop.enabled:true}")
    private boolean enabled;

    /** 裁剪成功后是否将原图另存为 original 类型图片资产 */
    @Value("${rsdp.ai.subject-crop.keep-original:true}")
    private boolean keepOriginal;

    /** bbox 面积下限（相对全图），低于则视为误检不裁剪（默认 0.02：整页型录截图中完整产品可能只占几个百分点） */
    @Value("${rsdp.ai.subject-crop.min-area-ratio:0.02}")
    private double minAreaRatio;

    /** bbox 面积上限（相对全图），高于则说明图本身已是纯产品图，无需裁剪（默认 0.98，收窄组合图漏裁窗口） */
    @Value("${rsdp.ai.subject-crop.max-area-ratio:0.98}")
    private double maxAreaRatio;

    /** 裁剪后是否再做一次 AI 完整性校验（不完整时加大框重裁或回退原图） */
    @Value("${rsdp.ai.subject-crop.verify-crop:true}")
    private boolean verifyCrop;

    /** 裁剪前外扩比例（相对全图宽高；录入主图比 PDF 导入更保守，防止切断部件） */
    private static final double CROP_EXPAND_RATIO = 0.05;

    /** 完整性校验不通过时的加大外扩比例 */
    private static final double RETRY_EXPAND_RATIO = 0.10;

    /** 收紧后留白比例（与 PDF 导入一致） */
    private static final double CROP_PAD_RATIO = 0.02;

    /** 输出 JPEG 质量 */
    private static final float OUTPUT_QUALITY = 0.9f;

    /**
     * 识别并裁剪图片中的产品主体。
     *
     * @param imageBytes 原始图片字节
     * @return 裁剪精修后的 JPEG 字节；检测失败/无需裁剪/功能关闭时返回 empty
     */
    public Optional<byte[]> cropToSubject(byte[] imageBytes) {
        if (!enabled) {
            return Optional.empty();
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                log.warn("图片解码失败，跳过主体裁剪");
                return Optional.empty();
            }

            ProductBoundingBox detected = visionService.detectProductSubject(
                new ByteArrayInputStream(imageBytes));
            if (detected == null) {
                log.info("AI 未检出明确产品主体，保留原图");
                return Optional.empty();
            }

            List<ProductBoxRefiner.Refined<ProductBoundingBox>> refined = ProductBoxRefiner.refineAll(
                List.of(detected), b -> b, image.getWidth(), image.getHeight());
            if (refined.isEmpty()) {
                log.info("产品主体 bbox 未通过清洗，保留原图");
                return Optional.empty();
            }

            ProductBoundingBox bbox = refined.get(0).box();
            double areaRatio = bbox.getWidth() * bbox.getHeight();
            if (areaRatio < minAreaRatio || areaRatio > maxAreaRatio) {
                log.info("产品主体面积占比 {} 超出阈值 [{}, {}]，保留原图",
                    String.format("%.2f", areaRatio), minAreaRatio, maxAreaRatio);
                return Optional.empty();
            }

            byte[] cropped = cropWithTrim(image, bbox, CROP_EXPAND_RATIO);

            // AI 完整性校验：裁剪结果有部件被切断时，加大外扩框重裁一次；仍不完整则回退原图
            if (verifyCrop && !visionService.isProductComplete(new ByteArrayInputStream(cropped))) {
                log.info("裁剪图完整性校验未通过，加大外扩框重裁，bbox={}", bbox);
                byte[] retried = cropWithTrim(image, bbox, RETRY_EXPAND_RATIO);
                if (visionService.isProductComplete(new ByteArrayInputStream(retried))) {
                    cropped = retried;
                } else {
                    log.warn("加大框重裁后产品仍不完整，回退原图，bbox={}", bbox);
                    return Optional.empty();
                }
            }

            log.info("产品主图裁剪完成，bbox={}，原图 {} 字节 -> 裁剪图 {} 字节",
                bbox, imageBytes.length, cropped.length);
            return Optional.of(cropped);
        } catch (Exception e) {
            log.warn("产品主体裁剪失败，回退原图：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 按指定外扩比例裁剪并保守精修（防细腿/浅色产品误切）。 */
    private byte[] cropWithTrim(BufferedImage image, ProductBoundingBox bbox, double expandRatio) throws IOException {
        return ImageWhitespaceTrimmer.cropRefineToJpeg(
            image, bbox, expandRatio, CROP_PAD_RATIO, OUTPUT_QUALITY,
            ImageWhitespaceTrimmer.TrimOptions.conservative());
    }

    /**
     * 尝试将已入库的主图替换为 AI 裁剪的产品主体图。
     *
     * <p>裁剪成功时：</p>
     * <ol>
     *   <li>裁剪图写入新对象键 {@code images/{imageId}.jpg}，主图 image_assets 行改指向它
     *       （imageId 不变，前端主图 URL 无感知），并回填宽高/大小/格式；</li>
     *   <li>{@code keepOriginal=true} 时，把指向原图的资产行另存为 original 类型（非主图），
     *       保留溯源与后续"恢复原图"能力；否则删除原图文件；</li>
     *   <li>返回裁剪后的字节，供后续 AI 识别与向量计算使用，保证语义一致。</li>
     * </ol>
     *
     * <p>任何失败都只记日志并返回 empty，已存储的原图不受影响。</p>
     *
     * @param originalBytes  原图字节
     * @param rspuId         RSPU ID
     * @param variantId      变体 ID（可为空）
     * @param primaryImageId 主图图片 ID
     * @param objectKey      主图当前存储对象键
     * @return 裁剪后的 JPEG 字节；未裁剪时返回 empty（主图保持原样）
     */
    public Optional<byte[]> cropAndReplacePrimary(byte[] originalBytes, String rspuId, String variantId,
                                                  String primaryImageId, String objectKey) {
        Optional<byte[]> croppedOpt = cropToSubject(originalBytes);
        if (croppedOpt.isEmpty()) {
            return Optional.empty();
        }
        byte[] cropped = croppedOpt.get();

        try {
            String croppedKey = "images/" + primaryImageId + ".jpg";
            storageService.store(new ByteArrayInputStream(cropped), croppedKey, cropped.length, "image/jpeg");

            String operator = SecurityOperatorContext.currentUsername();
            ImageAssets primary = imageAssetsMapper.selectById(primaryImageId);
            if (primary == null) {
                log.warn("主图资产记录不存在，imageId={}", primaryImageId);
                return Optional.empty();
            }

            if (keepOriginal) {
                // 原图文件保留在原 objectKey，登记为 original 类型资产
                ImageAssets original = new ImageAssets();
                original.setImageId(IdGenerator.imageId());
                original.setRspuId(rspuId);
                original.setVariantId(variantId != null ? variantId : primary.getVariantId());
                original.setImageType("original");
                original.setStoragePath(objectKey);
                original.setPrimary(false);
                original.setAiProcessed(false);
                original.setContentHash(primary.getContentHash());
                original.setFileSize((long) originalBytes.length);
                original.setFormat(extensionOf(objectKey));
                original.setUploadedBy(operator);
                original.setCreatedAt(LocalDateTime.now());
                fillDimensions(original, originalBytes);
                imageAssetsMapper.insert(original);
                auditLogService.logCreate("image_assets", original.getImageId(), original, operator);
            } else {
                storageService.delete(objectKey);
            }

            // 主图改指裁剪图并回填元数据
            primary.setStoragePath(croppedKey);
            primary.setFormat("jpg");
            primary.setFileSize((long) cropped.length);
            fillDimensions(primary, cropped);
            imageAssetsMapper.updateById(primary);

            log.info("主图已替换为 AI 裁剪图，imageId={}，rspuId={}", primaryImageId, rspuId);
            return Optional.of(cropped);
        } catch (Exception e) {
            log.warn("主图裁剪替换失败，保留原图，imageId={}：{}", primaryImageId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 解码图片并回填宽高元数据（image_assets.width/height 此前从未写入）。 */
    private void fillDimensions(ImageAssets asset, byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                asset.setWidth(img.getWidth());
                asset.setHeight(img.getHeight());
            }
        } catch (Exception e) {
            log.debug("回填图片宽高失败，imageId={}", asset.getImageId());
        }
    }

    /** 从对象键提取扩展名（无扩展名时默认 jpg）。 */
    private String extensionOf(String objectKey) {
        int dot = objectKey != null ? objectKey.lastIndexOf('.') : -1;
        return dot >= 0 ? objectKey.substring(dot + 1) : "jpg";
    }
}
