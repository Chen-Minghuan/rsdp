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

    /** PDF Content-Type。 */
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    /** PDF 扩展名。 */
    private static final String PDF_EXTENSION = "pdf";

    /**
     * 户型图链路上传类型（{@link #validateImageOrPdf} 的判定结果）。
     */
    public enum UploadKind {
        /** 图片（走既有图片校验规则）。 */
        IMAGE,
        /** PDF（由调用方渲染首页为图片后进入识别管线，PDF 原文件不留存）。 */
        PDF
    }

    /**
     * PDF 感知校验（户型图链路 v3.0 §8 P2 专用重载）：图片走既有 {@link #validate}
     * 规则不变；content-type 为 application/pdf 或扩展名为 .pdf 时按 PDF 放行
     * （仅做空文件/大小校验，内容由调用方渲染时核验）。
     *
     * <p>注意：本方法仅户型图双端 analyze 入口使用，其他上传入口仍只接受图片。</p>
     *
     * @param file         上传文件
     * @param maxSizeBytes 最大允许字节数
     * @return 上传类型（IMAGE / PDF）
     */
    public UploadKind validateImageOrPdf(MultipartFile file, long maxSizeBytes) {
        String contentType = file != null && file.getContentType() != null
            ? file.getContentType().toLowerCase(Locale.ROOT) : null;
        String extension = file != null ? getExtension(file.getOriginalFilename()) : "";
        if (PDF_CONTENT_TYPE.equals(contentType) || PDF_EXTENSION.equals(extension)) {
            if (file.isEmpty()) {
                throw new BusinessException("请上传图片或 PDF 文件");
            }
            if (file.getSize() > maxSizeBytes) {
                throw new BusinessException("文件大小超过限制");
            }
            return UploadKind.PDF;
        }
        validate(file, maxSizeBytes);
        return UploadKind.IMAGE;
    }

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
