package com.rsdp.service;

import com.rsdp.dto.response.CmsImageUploadResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.IdGenerator;
import com.rsdp.util.ImageUploadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * CMS 图片上传服务：官网 Banner/案例/定制等图片素材上传。
 *
 * <p>图片落 image_assets（image_type=cms，不关联 RSPU），经 StorageService 存储。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformImageService {

    /** 单张图片大小上限（10MB）。 */
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;

    private final ImageUploadValidator imageUploadValidator;
    private final StorageService storageService;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AuditLogService auditLogService;

    /**
     * 上传 CMS 图片素材。
     *
     * @param file 图片文件
     * @return 图片 ID 与访问地址
     * @throws IOException 存储失败时抛出
     */
    @Transactional
    public CmsImageUploadResponse upload(MultipartFile file) throws IOException {
        imageUploadValidator.validate(file, MAX_IMAGE_SIZE_BYTES);

        String imageId = IdGenerator.imageId();
        String extension = getExtension(file.getOriginalFilename());
        String objectKey = "cms/" + imageId + "." + extension;
        String storagePath = storageService.store(file, objectKey);

        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setImageType("cms");
        imageAsset.setStoragePath(storagePath);
        imageAsset.setPrimary(false);
        imageAsset.setAiProcessed(false);
        imageAsset.setFileSize(file.getSize());
        imageAsset.setFormat(extension);
        imageAsset.setUploadedBy(SecurityOperatorContext.currentUsername());
        imageAsset.setCreatedAt(LocalDateTime.now());
        imageAssetsMapper.insert(imageAsset);
        auditLogService.logCreate("image_assets", imageId, imageAsset, SecurityOperatorContext.currentUsername());

        return new CmsImageUploadResponse(imageId, "/api/v1/images/" + imageId);
    }

    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "jpg";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase(Locale.ROOT);
    }
}
