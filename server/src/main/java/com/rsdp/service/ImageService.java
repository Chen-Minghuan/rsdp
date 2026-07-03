package com.rsdp.service;

import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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

    /**
     * 加载图片结果，包含资源流与 MIME 类型。
     */
    public record LoadedImage(Resource resource, String contentType) {
    }

    /**
     * 根据图片 ID 加载图片文件资源与 MIME 类型。
     *
     * @param imageId 图片 ID
     * @return 加载结果
     */
    public LoadedImage loadImageResource(String imageId) {
        ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
        if (imageAsset == null) {
            throw new ResourceNotFoundException("图片不存在: " + imageId);
        }

        String objectKey = imageAsset.getStoragePath();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ResourceNotFoundException("图片存储路径为空: " + imageId);
        }

        try {
            String filename = imageId + "." + (imageAsset.getFormat() != null ? imageAsset.getFormat() : "jpg");
            Resource resource = new InputStreamResource(storageService.get(objectKey));
            return new LoadedImage(resource, resolveContentType(filename));
        } catch (IOException e) {
            throw new ResourceNotFoundException("图片读取失败: " + imageId);
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
