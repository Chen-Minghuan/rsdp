package com.rsdp.util;

import com.alibaba.excel.EasyExcel;
import com.rsdp.dto.excel.ProductImportRow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExcelFileValidator} 单元测试。
 */
class ExcelFileValidatorTest {

    @Test
    void isExcelOrCsv_shouldAcceptXlsx() {
        byte[] bytes = createXlsxBytes();
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
            "application/octet-stream", bytes);

        assertThat(ExcelFileValidator.isExcelOrCsv(file)).isTrue();
    }

    @Test
    void isExcelOrCsv_shouldAcceptCsv() {
        String csv = "品类码,外部编码\nFS,EXT-001\n";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv",
            "application/octet-stream", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(ExcelFileValidator.isExcelOrCsv(file)).isTrue();
    }

    @Test
    void isExcelOrCsv_shouldRejectBinary() {
        byte[] bytes = new byte[1024];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 256);
        }
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        assertThat(ExcelFileValidator.isExcelOrCsv(file)).isFalse();
    }

    @Test
    void isExcelOrCsv_shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        assertThat(ExcelFileValidator.isExcelOrCsv(file)).isFalse();
    }

    @Test
    void isExcelOrCsv_shouldRejectPlainTextWithoutDelimiter() {
        String text = "this is not a csv";
        MockMultipartFile file = new MockMultipartFile("file", "test.txt",
            "text/plain", text.getBytes(StandardCharsets.UTF_8));

        assertThat(ExcelFileValidator.isExcelOrCsv(file)).isFalse();
    }

    private byte[] createXlsxBytes() {
        ProductImportRow row = new ProductImportRow();
        row.setCategoryCode("FS");
        row.setExternalCode("EXT-001");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        EasyExcel.write(outputStream, ProductImportRow.class).sheet("Sheet1").doWrite(List.of(row));
        return outputStream.toByteArray();
    }
}
