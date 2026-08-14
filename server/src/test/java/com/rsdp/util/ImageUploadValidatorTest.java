package com.rsdp.util;

import com.rsdp.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImageUploadValidator} 单元测试。
 */
class ImageUploadValidatorTest {

    private final ImageUploadValidator validator = new ImageUploadValidator();

    @Test
    void shouldAcceptValidJpeg() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake".getBytes()
        );
        assertThatNoException().isThrownBy(() -> validator.validate(file, 1024 * 1024));
    }

    @Test
    void shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "empty.jpg", "image/jpeg", new byte[0]
        );
        assertThatThrownBy(() -> validator.validate(file, 1024 * 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请上传图片文件");
    }

    @Test
    void shouldRejectOversizedFile() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "big.jpg", "image/jpeg", "x".getBytes()
        );
        assertThatThrownBy(() -> validator.validate(file, 0))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片大小超过限制");
    }

    @Test
    void shouldRejectNonImageContentType() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "doc.pdf", "application/pdf", "pdf".getBytes()
        );
        assertThatThrownBy(() -> validator.validate(file, 1024 * 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片格式");
    }

    @Test
    void shouldRejectUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "doc.pdf", "image/jpeg", "fake".getBytes()
        );
        assertThatThrownBy(() -> validator.validate(file, 1024 * 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅支持");
    }

    // ---------- PDF 感知重载（户型图链路 v3.0 §8 P2 专用） ----------

    @Test
    void validateImageOrPdf_pdfContentType_shouldReturnPdf() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.pdf", "application/pdf", "pdf".getBytes()
        );
        org.assertj.core.api.Assertions.assertThat(
            validator.validateImageOrPdf(file, 1024 * 1024))
            .isEqualTo(ImageUploadValidator.UploadKind.PDF);
    }

    @Test
    void validateImageOrPdf_pdfExtensionWithGenericContentType_shouldReturnPdf() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "plan.pdf", "application/octet-stream", "pdf".getBytes()
        );
        org.assertj.core.api.Assertions.assertThat(
            validator.validateImageOrPdf(file, 1024 * 1024))
            .isEqualTo(ImageUploadValidator.UploadKind.PDF);
    }

    @Test
    void validateImageOrPdf_image_shouldReturnImage() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake".getBytes()
        );
        org.assertj.core.api.Assertions.assertThat(
            validator.validateImageOrPdf(file, 1024 * 1024))
            .isEqualTo(ImageUploadValidator.UploadKind.IMAGE);
    }

    @Test
    void validateImageOrPdf_oversizedPdf_shouldReject() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "big.pdf", "application/pdf", "x".getBytes()
        );
        assertThatThrownBy(() -> validator.validateImageOrPdf(file, 0))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("大小超过限制");
    }

    @Test
    void validateImageOrPdf_emptyPdf_shouldReject() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "empty.pdf", "application/pdf", new byte[0]
        );
        assertThatThrownBy(() -> validator.validateImageOrPdf(file, 1024 * 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请上传图片或 PDF 文件");
    }

    @Test
    void validateImageOrPdf_nonImageNonPdf_shouldKeepRejecting() {
        // 其他上传入口行为不变的重载侧写：非图片非 PDF 仍按图片规则拒绝
        MockMultipartFile file = new MockMultipartFile(
            "image", "doc.docx", "application/msword", "fake".getBytes()
        );
        assertThatThrownBy(() -> validator.validateImageOrPdf(file, 1024 * 1024))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片格式");
    }
}
