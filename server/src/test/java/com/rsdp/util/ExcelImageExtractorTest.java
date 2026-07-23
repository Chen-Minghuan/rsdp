package com.rsdp.util;

import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 内嵌图片提取工具测试。
 */
class ExcelImageExtractorTest {

    @Test
    void extract_shouldReturnEmbeddedImagesGroupedByRow() throws IOException {
        byte[] excelBytes = createExcelWithEmbeddedImages();
        MultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        Map<String, List<ExcelImageExtractor.EmbeddedImage>> result = ExcelImageExtractor.extract(file);

        assertNotNull(result);
        assertTrue(result.containsKey("0,1"), "第 1 行（Excel 物理行 1）应该有内嵌图片");
        assertTrue(result.containsKey("0,2"), "第 2 行（Excel 物理行 2）应该有内嵌图片");
        assertFalse(result.containsKey("0,0"), "表头行不应该有图片");

        List<ExcelImageExtractor.EmbeddedImage> row1Images = result.get("0,1");
        assertEquals(1, row1Images.size());
        assertTrue(row1Images.get(0).bytes().length > 0);
        assertEquals("png", row1Images.get(0).extension().toLowerCase());
        assertEquals(1, row1Images.get(0).colIndex());

        List<ExcelImageExtractor.EmbeddedImage> row2Images = result.get("0,2");
        assertEquals(1, row2Images.size());
        assertEquals("png", row2Images.get(0).extension().toLowerCase());
    }

    @Test
    void extract_emptyFile_shouldReturnEmptyMap() throws IOException {
        MultipartFile file = new MockMultipartFile("empty.xlsx", "empty.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);
        Map<String, List<ExcelImageExtractor.EmbeddedImage>> result = ExcelImageExtractor.extract(file);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void extract_invalidFile_shouldThrowIOException() {
        // 整体性失败（文件损坏）向上抛，由调用方记录告警，不再静默返回空
        MultipartFile file = new MockMultipartFile("bad.xlsx", "bad.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "not-an-excel-file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
            () -> ExcelImageExtractor.extract(file));
    }

    @Test
    void extract_shouldSkipNonWebImageFormats() throws IOException {
        // P2-12c：EMF/WMF/TIFF 在提取阶段直接跳过（不占用配额、不进结果），PNG 不受影响
        byte[] excelBytes = createExcelWithPngAndEmf();
        MultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        Map<String, List<ExcelImageExtractor.EmbeddedImage>> result = ExcelImageExtractor.extract(file);

        assertNotNull(result);
        assertTrue(result.containsKey("0,1"), "PNG 所在行应提取到图片");
        assertEquals("png", result.get("0,1").get(0).extension().toLowerCase());
        assertFalse(result.containsKey("0,2"), "EMF 所在行不应提取到图片（提取阶段已跳过）");
    }

    @Test
    void extract_shouldProcessAllSheets() throws IOException {
        // 多 Sheet 导入：提取器遍历全部 Sheet，sheet≥1 的图片以 "sheetIndex,row" key 入结果，
        // 由消费侧按批次 sheet_index 取本工作表的图
        byte[] excelBytes = createExcelWithImageOnSecondSheet();
        MultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        Map<String, List<ExcelImageExtractor.EmbeddedImage>> result = ExcelImageExtractor.extract(file);

        assertNotNull(result);
        assertTrue(result.containsKey("1,1"), "sheet 1 的图片应以 sheetIndex=1 的 key 提取: " + result.keySet());
        assertEquals(1, result.get("1,1").get(0).sheetIndex());
    }

    @Test
    void extractWithLimit_shouldReportByteTruncationReason() throws IOException {
        // P2-12d：截断原因区分「总字节超限」
        byte[] excelBytes = createExcelWithEmbeddedImages();
        MultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        ExcelImageExtractor.ExtractionResult result =
            ExcelImageExtractor.extractWithLimit(file, createPngImageBytes().length);

        assertTrue(result.truncated(), "超过上限应标记截断");
        assertTrue(result.truncationReason() != null && result.truncationReason().contains("总大小"),
            "截断原因应说明总字节超限: " + result.truncationReason());
        int totalImages = result.images().values().stream().mapToInt(List::size).sum();
        assertEquals(1, totalImages, "应保留截断前已提取的第一张图");
    }

    @Test
    void extract_shouldTruncateAndKeepPartialWhenExceedingLimit() throws IOException {
        // 上限恰好容纳第一张图：第二张被截断，已提取部分保留，truncated 标记为 true
        byte[] excelBytes = createExcelWithEmbeddedImages();
        MultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        boolean[] truncated = new boolean[1];
        Map<String, List<ExcelImageExtractor.EmbeddedImage>> result =
            ExcelImageExtractor.extract(file, createPngImageBytes().length, truncated);

        assertTrue(truncated[0], "超过上限应标记截断");
        int totalImages = result.values().stream().mapToInt(List::size).sum();
        assertEquals(1, totalImages, "应保留截断前已提取的第一张图");
    }

    private byte[] createExcelWithImageOnSecondSheet() throws IOException {
        // sheet 0 无图，sheet 1 有一张 PNG：验证提取侧遍历全部 Sheet
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet0 = workbook.createSheet("Sheet1");
            sheet0.createRow(0).createCell(0).setCellValue("产品名称");
            Sheet sheet1 = workbook.createSheet("Sheet2");
            sheet1.createRow(0).createCell(0).setCellValue("其他");

            Drawing<?> drawing = sheet1.createDrawingPatriarch();
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(1);
            anchor.setRow1(1);
            drawing.createPicture(anchor,
                workbook.addPicture(createPngImageBytes(), Workbook.PICTURE_TYPE_PNG));

            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void extract_shouldSkipNegativeAnchorRow() throws IOException {
        // P2-16：负锚点行跳过，不归到第 0 行
        byte[] excelBytes = createExcelWithNegativeAnchorImage();
        MultipartFile file = new MockMultipartFile("test.xlsx", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelBytes);

        Map<String, List<ExcelImageExtractor.EmbeddedImage>> result = ExcelImageExtractor.extract(file);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "负锚点行的图片应被跳过: " + result.keySet());
    }

    private byte[] createExcelWithNegativeAnchorImage() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("产品名称");
            sheet.createRow(1).createCell(0).setCellValue("产品A");

            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(1);
            anchor.setRow1(-1); // 负锚点行：无法对齐数据行
            drawing.createPicture(anchor,
                workbook.addPicture(createPngImageBytes(), Workbook.PICTURE_TYPE_PNG));

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] createExcelWithPngAndEmf() throws IOException {
        byte[] emfBytes = new byte[]{0x01, 0x00, 0x00, 0x00, 0x58, 0x02, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04};
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("产品名称");
            sheet.createRow(1).createCell(0).setCellValue("产品A");
            sheet.createRow(2).createCell(0).setCellValue("产品B");

            Drawing<?> drawing = sheet.createDrawingPatriarch();

            ClientAnchor anchor1 = workbook.getCreationHelper().createClientAnchor();
            anchor1.setCol1(1);
            anchor1.setRow1(1);
            drawing.createPicture(anchor1,
                workbook.addPicture(createPngImageBytes(), Workbook.PICTURE_TYPE_PNG));

            ClientAnchor anchor2 = workbook.getCreationHelper().createClientAnchor();
            anchor2.setCol1(1);
            anchor2.setRow1(2);
            drawing.createPicture(anchor2,
                workbook.addPicture(emfBytes, Workbook.PICTURE_TYPE_EMF));

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] createExcelWithEmbeddedImages() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("产品名称");
            sheet.createRow(1).createCell(0).setCellValue("产品A");
            sheet.createRow(2).createCell(0).setCellValue("产品B");

            byte[] imageBytes = createPngImageBytes();
            int pictureIdx = workbook.addPicture(imageBytes, Workbook.PICTURE_TYPE_PNG);
            Drawing<?> drawing = sheet.createDrawingPatriarch();

            ClientAnchor anchor1 = workbook.getCreationHelper().createClientAnchor();
            anchor1.setCol1(1);
            anchor1.setRow1(1);
            drawing.createPicture(anchor1, pictureIdx);

            ClientAnchor anchor2 = workbook.getCreationHelper().createClientAnchor();
            anchor2.setCol1(1);
            anchor2.setRow1(2);
            drawing.createPicture(anchor2, pictureIdx);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] createPngImageBytes() {
        // 1x1 像素的 PNG 图片
        return new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
            (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF,
            (byte) 0xC0, 0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x00,
            0x05, (byte) 0xFE, (byte) 0xD8, 0x22, 0x00, 0x00, 0x00,
            0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60,
            (byte) 0x82
        };
    }
}
