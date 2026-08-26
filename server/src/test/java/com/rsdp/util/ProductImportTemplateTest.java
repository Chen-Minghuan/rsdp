package com.rsdp.util;

import com.rsdp.dto.excel.ProductImportRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductImportTemplateBuilder} 单元测试。
 */
class ProductImportTemplateTest {

    private XSSFWorkbook buildTemplate() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProductImportTemplateBuilder.write(out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void sheet1HeadersMatchExcelPropertyAnnotations() throws Exception {
        try (XSSFWorkbook workbook = buildTemplate()) {
            Sheet sheet = workbook.getSheet(ProductImportTemplateBuilder.TEMPLATE_SHEET_NAME);
            assertThat(sheet).isNotNull();
            // 只写表头，不写示例数据行
            assertThat(sheet.getLastRowNum()).isZero();

            List<String> expected = ProductImportTemplateBuilder.headerNames();
            assertThat(expected).isNotEmpty();

            Row header = sheet.getRow(0);
            List<String> actual = new ArrayList<>();
            for (int i = 0; i < expected.size(); i++) {
                actual.add(header.getCell(i).getStringCellValue());
            }
            // 表头逐列与 @ExcelProperty 值完全一致（防解析口径漂移回归）
            assertThat(actual).containsExactlyElementsOf(expected);
            // 关键列均在模板中
            assertThat(actual).contains("品类码", "零售参考价", "尺寸原文", "颜色原文", "材质原文");
        }
    }

    @Test
    void sheet2ContainsInstructionLegend() throws Exception {
        try (XSSFWorkbook workbook = buildTemplate()) {
            Sheet sheet = workbook.getSheet(ProductImportTemplateBuilder.INSTRUCTION_SHEET_NAME);
            assertThat(sheet).isNotNull();

            boolean hasRequiredLegend = false;
            boolean hasUpdateModeNote = false;
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.STRING) {
                        continue;
                    }
                    String value = cell.getStringCellValue();
                    if (value.contains("必填")) {
                        hasRequiredLegend = true;
                    }
                    if (value.contains("updateIfExists") && value.contains("不覆盖")) {
                        hasUpdateModeNote = true;
                    }
                }
            }
            assertThat(hasRequiredLegend).as("图例应说明必填列").isTrue();
            assertThat(hasUpdateModeNote).as("应说明更新模式空单元格不覆盖已有数据").isTrue();
        }
    }

    @Test
    void requiredAndRecommendedHeaderStyles() throws Exception {
        try (XSSFWorkbook workbook = buildTemplate()) {
            Sheet sheet = workbook.getSheet(ProductImportTemplateBuilder.TEMPLATE_SHEET_NAME);
            Row header = sheet.getRow(0);
            List<String> heads = ProductImportTemplateBuilder.headerNames();

            // 品类码：红色加粗（必填）
            Font requiredFont = workbook.getFontAt(
                header.getCell(heads.indexOf("品类码")).getCellStyle().getFontIndex());
            assertThat(requiredFont.getBold()).isTrue();
            assertThat(requiredFont.getColor()).isEqualTo(IndexedColors.RED.getIndex());

            // 定位标签：橙色加粗（建议）
            Font recommendedFont = workbook.getFontAt(
                header.getCell(heads.indexOf("定位标签")).getCellStyle().getFontIndex());
            assertThat(recommendedFont.getBold()).isTrue();
            assertThat(recommendedFont.getColor()).isEqualTo(IndexedColors.ORANGE.getIndex());
        }
    }
}
