package com.rsdp.util;

import com.rsdp.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PdfFileValidator} 单元测试。
 */
class PdfFileValidatorTest {

    @Test
    void validate_shouldAcceptValidPdf() throws IOException {
        byte[] bytes = createPdfBytes(5);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", bytes);

        PdfFileValidator.validate(file, 10 * 1024 * 1024, 200);
    }

    @Test
    void validate_shouldRejectNonPdf() {
        MockMultipartFile file = new MockMultipartFile("file", "catalog.txt", "text/plain",
            "not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PdfFileValidator.validate(file, 10 * 1024 * 1024, 200))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅支持 PDF");
    }

    @Test
    void validate_shouldRejectOversizedFile() throws IOException {
        byte[] bytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> PdfFileValidator.validate(file, 1, 200))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("大小超过限制");
    }

    @Test
    void validate_shouldRejectTooManyPages() throws IOException {
        byte[] bytes = createPdfBytes(10);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> PdfFileValidator.validate(file, 10 * 1024 * 1024, 5))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("页数不能超过");
    }

    @Test
    void isPdf_shouldReturnTrueForPdf() throws IOException {
        byte[] bytes = createPdfBytes(1);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", bytes);

        assertThat(PdfFileValidator.isPdf(file)).isTrue();
    }

    @Test
    void isPdf_shouldReturnFalseForText() {
        MockMultipartFile file = new MockMultipartFile("file", "catalog.txt", "text/plain",
            "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(PdfFileValidator.isPdf(file)).isFalse();
    }

    @Test
    void countPages_shouldReturnCorrectNumber() throws IOException {
        byte[] bytes = createPdfBytes(7);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", bytes);

        assertThat(PdfFileValidator.countPages(file)).isEqualTo(7);
    }

    private byte[] createPdfBytes(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
