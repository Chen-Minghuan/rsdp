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
}
