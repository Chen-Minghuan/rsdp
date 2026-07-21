package com.rsdp.service;

import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.DesignOrderItemMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RskuSupplyMapper;
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

    private final ImageAssetsMapper imageAssetsMapper;
    private final StorageService storageService;
    private final DataScopeHelper dataScopeHelper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final DesignOrderItemMapper designOrderItemMapper;

    /**
     * 加载图片结果，包含资源流与 MIME 类型。
     */
    public record LoadedImage(Resource resource, String contentType) {
    }

    /**
     * 根据图片 ID 加载图片文件资源与 MIME 类型。
     *
     * <p>仅允许已登录且对图片关联 RSPU/RSKU 有数据权限的用户访问。</p>
     *
     * @param imageId 图片 ID
     * @return 加载结果
     */
    public LoadedImage loadImageResource(String imageId) {
        ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
        if (imageAsset == null || imageAsset.getDeletedAt() != null) {
            throw new ResourceNotFoundException("图片不存在: " + imageId);
        }
        assertLoggedInUserCanAccess(imageAsset);
        return doLoad(imageAsset);
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
     */
    private void assertLoggedInUserCanAccess(ImageAssets imageAsset) {
        if (!SecurityOperatorContext.isAuthenticated()) {
            throw new ResourceNotFoundException("图片不存在: " + imageAsset.getImageId());
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
