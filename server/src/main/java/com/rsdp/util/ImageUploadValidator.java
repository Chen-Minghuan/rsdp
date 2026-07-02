package com.rsdp.util;

import com.rsdp.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * 图片上传校验器。
 */
@Component
public class ImageUploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "webp", "gif", "bmp"
    );

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp"
    );

    /**
     * 校验上传文件是否为允许的图片。
     *
     * @param file        上传文件
     * @param maxSizeBytes 最大允许字节数
     */
    public void validate(MultipartFile file, long maxSizeBytes) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传图片文件");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException("图片大小超过限制");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException("请上传图片格式文件");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("仅支持 jpg、png、webp、gif、bmp 格式图片");
        }
        String extension = getExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("仅支持 jpg、png、webp、gif、bmp 格式图片");
        }
    }

    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase(Locale.ROOT);
    }
}
