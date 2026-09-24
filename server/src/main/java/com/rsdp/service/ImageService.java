package com.rsdp.service;

import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 图片文件服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final String FLOOR_PLAN_IMAGE_TYPE = "floor_plan";
    private static final String FLOOR_PLAN_CAD_PREVIEW_IMAGE_TYPE = "floor_plan_cad_preview";

    private final ImageAssetsMapper imageAssetsMapper;
    private final FloorPlanAnalysisMapper floorPlanAnalysisMapper;
    private final StorageService storageService;
    private final DataScopeHelper dataScopeHelper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final DesignOrderItemMapper designOrderItemMapper;
    private final RspuMapper rspuMasterMapper;

    /**
     * 加载图片结果，包含资源流与 MIME 类型。
     */
    public record LoadedImage(Resource resource, String contentType) {
    }

    /**
     * 根据图片 ID 加载图片文件资源与 MIME 类型。
     *
     * <p>公开资源（CMS 运营图、在售产品图，即 /api/v1/public/** 已公开引用的图片）
     * 允许匿名访问；户型原图及 CAD 规范预览按分析记录归属访问；其余图片要求当前
     * 用户对图片关联 RSPU/RSKU 具有数据权限。</p>
     *
     * @param imageId 图片 ID
     * @return 加载结果
     */
    public LoadedImage loadImageResource(String imageId) {
        ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
        if (imageAsset == null || imageAsset.getDeletedAt() != null) {
            throw new ResourceNotFoundException("图片不存在: " + imageId);
        }
        if (!isPubliclyVisible(imageAsset)) {
            assertLoggedInUserCanAccess(imageAsset);
        }
        return doLoad(imageAsset);
    }

    /**
     * 判断图片是否为公开可访问资源（官网匿名访问场景）。
     *
     * <p>判定规则：CMS 运营图（image_type=cms，专为官网公开配置），或归属于
     * 在售（status=active）产品的产品图（官网商品主图已在公开接口暴露）。
     * 户型文件包含用户隐私，不属于公开资源。</p>
     *
     * @param imageAsset 图片实体
     * @return true 表示允许匿名访问
     */
    private boolean isPubliclyVisible(ImageAssets imageAsset) {
        if ("cms".equals(imageAsset.getImageType())) {
            return true;
        }
        String rspuId = imageAsset.getRspuId();
        if (StringUtils.hasText(rspuId)) {
            RspuMaster rspu = rspuMasterMapper.selectById(rspuId);
            return rspu != null && "active".equals(rspu.getStatus());
        }
        return false;
    }

    /**
     * 根据图片 ID 加载图片文件资源（订单邀请公开场景）。
     *
     * <p>不校验登录状态，仅校验该图片是否属于指定订单的明细。</p>
     *
     * @param imageId 图片 ID
     * @param orderId 订单 ID
     * @return 加载结果
     */
    public LoadedImage loadImageResourceForInvite(String imageId, String orderId) {
        ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
        if (imageAsset == null || imageAsset.getDeletedAt() != null) {
            throw new ResourceNotFoundException("图片不存在: " + imageId);
        }
        if (!imageBelongsToOrder(imageAsset, orderId)) {
            throw new ResourceNotFoundException("图片不存在: " + imageId);
        }
        return doLoad(imageAsset);
    }

    /**
     * 使用已验证的游客 CAD 分析 ID 加载其户型原图或规范预览。
     *
     * @param imageId    图片 ID
     * @param analysisId 游客访问凭证绑定的分析 ID
     * @return 加载结果
     */
    public LoadedImage loadImageResourceForPublicFloorPlan(String imageId, String analysisId) {
        ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
        if (imageAsset == null || imageAsset.getDeletedAt() != null || !isFloorPlanAsset(imageAsset)) {
            throw new ResourceNotFoundException("图片不存在: " + imageId);
        }
        FloorPlanAnalysis analysis = floorPlanAnalysisMapper.selectById(analysisId);
        if (analysis == null || !"public".equals(analysis.getSource())
            || (!imageId.equals(analysis.getImageId()) && !imageId.equals(analysis.getPreviewImageId()))) {
            throw new ResourceNotFoundException("图片不存在: " + imageId);
        }
        return doLoad(imageAsset);
    }

    private LoadedImage doLoad(ImageAssets imageAsset) {
        String objectKey = imageAsset.getStoragePath();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ResourceNotFoundException("图片存储路径为空: " + imageAsset.getImageId());
        }

        try {
            String filename = imageAsset.getImageId() + "." + (imageAsset.getFormat() != null ? imageAsset.getFormat() : "jpg");
            Resource resource = new InputStreamResource(storageService.get(objectKey));
            return new LoadedImage(resource, resolveContentType(filename));
        } catch (IOException e) {
            throw new ResourceNotFoundException("图片读取失败: " + imageAsset.getImageId());
        }
    }

    /**
     * 判断图片是否被指定订单引用。
     */
    private boolean imageBelongsToOrder(ImageAssets imageAsset, String orderId) {
        if (imageAsset.getImageId() == null) {
            return false;
        }
        Long count = designOrderItemMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.rsdp.entity.DesignOrderItem>()
                .eq("order_id", orderId)
                .eq("image_id", imageAsset.getImageId())
        );
        return count != null && count > 0;
    }

    /**
     * 断言当前登录用户可访问指定图片。
     *
     * <p>户型原图及 CAD 规范预览（image_type=floor_plan / floor_plan_cad_preview）
     * 由关联的 floor_plan_analysis 做归属校验：平台运营可访问全部，其他登录用户
     * 只能访问本人创建的分析记录所关联文件。</p>
     */
    private void assertLoggedInUserCanAccess(ImageAssets imageAsset) {
        if (!SecurityOperatorContext.isAuthenticated()) {
            throw new ResourceNotFoundException("图片不存在: " + imageAsset.getImageId());
        }
        if (isFloorPlanAsset(imageAsset)) {
            assertCanAccessFloorPlanAsset(imageAsset);
            return;
        }
        String rspuId = imageAsset.getRspuId();
        if (StringUtils.hasText(rspuId) && dataScopeHelper.canAccessRspu(rspuId)) {
            return;
        }
        String rskuId = imageAsset.getRskuId();
        if (StringUtils.hasText(rskuId)) {
            RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
            if (rsku != null && dataScopeHelper.canAccessFactory(rsku.getFactoryCode())) {
                return;
            }
        }
        throw new ResourceNotFoundException("图片不存在: " + imageAsset.getImageId());
    }

    /**
     * 判断资源是否为户型原图或 CAD 规范预览。
     *
     * @param imageAsset 图片实体
     * @return 是否为户型资源
     */
    private boolean isFloorPlanAsset(ImageAssets imageAsset) {
        return FLOOR_PLAN_IMAGE_TYPE.equals(imageAsset.getImageType())
            || FLOOR_PLAN_CAD_PREVIEW_IMAGE_TYPE.equals(imageAsset.getImageType());
    }

    /**
     * 校验当前用户对户型资源的归属访问权限。
     *
     * @param imageAsset 户型原图或 CAD 规范预览
     */
    private void assertCanAccessFloorPlanAsset(ImageAssets imageAsset) {
        if (SecurityOperatorContext.isPlatformStaff()) {
            return;
        }
        Long count = floorPlanAnalysisMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FloorPlanAnalysis>()
                .eq("created_by", SecurityOperatorContext.currentUsername())
                .isNull("deleted_at")
                .and(wrapper -> wrapper
                    .eq("image_id", imageAsset.getImageId())
                    .or()
                    .eq("preview_image_id", imageAsset.getImageId()))
        );
        if (count == null || count == 0) {
            throw new ResourceNotFoundException("图片不存在: " + imageAsset.getImageId());
        }
    }

    /**
     * 根据文件名后缀推断图片 MIME 类型。
     *
     * @param filename 文件名
     * @return MIME 类型
     */
    public String resolveContentType(String filename) {
        if (filename == null) {
            return "application/octet-stream";
        }
        String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }
}
