package com.rsdp.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 内嵌图片提取工具。
 *
 * <p>按 Sheet → Drawing → Picture 遍历，根据 ClientAnchor 定位到行，
 * 同一行内按列号排序，第一列的图片作为主图。</p>
 */
@Slf4j
public final class ExcelImageExtractor {

    private static final int MAX_SHEETS = 50;
    private static final int MAX_PICTURES = 1000;
    private static final long MAX_TOTAL_IMAGE_BYTES = 200 * 1024 * 1024L; // 200 MB

    private ExcelImageExtractor() {
    }

    /**
     * 从 Excel 文件中提取所有内嵌图片，并按行索引分组。
     *
     * <p>返回的 Map key 为「Sheet 索引, 行索引」（从 0 开始），
     * value 为该单元格行关联的图片列表，已按列号升序排列。</p>
     *
     * @param file Excel 文件
     * @return 按 sheetIndex+rowIndex 分组的内嵌图片
     * @throws IOException 读取文件失败
     */
    public static Map<String, List<EmbeddedImage>> extract(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<EmbeddedImage>> result = new HashMap<>();
        long totalImageBytes = 0;
        int pictureCount = 0;
        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {

            if (workbook.getNumberOfSheets() > MAX_SHEETS) {
                throw new IOException("Excel 文件 Sheet 数量超过上限 " + MAX_SHEETS);
            }

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Drawing<?> drawing = sheet.getDrawingPatriarch();
                if (drawing == null) {
                    continue;
                }

                for (org.apache.poi.ss.usermodel.Shape shape : drawing) {
                    if (!(shape instanceof Picture picture)) {
                        continue;
                    }
                    try {
                        ClientAnchor anchor = picture.getClientAnchor();
                        if (anchor == null) {
                            continue;
                        }

                        PictureData pictureData = picture.getPictureData();
                        if (pictureData == null || pictureData.getData() == null || pictureData.getData().length == 0) {
                            continue;
                        }

                        if (++pictureCount > MAX_PICTURES) {
                            throw new IOException("Excel 内嵌图片数量超过上限 " + MAX_PICTURES);
                        }
                        totalImageBytes += pictureData.getData().length;
                        if (totalImageBytes > MAX_TOTAL_IMAGE_BYTES) {
                            throw new IOException("Excel 内嵌图片总大小超过上限 " + MAX_TOTAL_IMAGE_BYTES + " 字节");
                        }

                        int rowIndex = Math.max(0, anchor.getRow1());
                        int colIndex = Math.max(0, anchor.getCol1());
                        String key = buildKey(sheetIndex, rowIndex);

                        String extension = resolveExtension(pictureData.suggestFileExtension());
                        EmbeddedImage image = new EmbeddedImage(
                            pictureData.getData(),
                            extension,
                            colIndex,
                            rowIndex,
                            sheetIndex
                        );
                        result.computeIfAbsent(key, k -> new ArrayList<>()).add(image);
                    } catch (IOException e) {
                        // 整体性失败（超上限）向上抛
                        throw e;
                    } catch (Exception e) {
                        // 单张图片损坏/无法解析时跳过，不影响其余图片提取
                        log.warn("跳过无法解析的内嵌图片，sheetIndex={}", sheetIndex, e);
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 整体性失败（文件损坏等）向上抛，由调用方记录告警
            throw new IOException("解析 Excel 内嵌图片失败: " + e.getMessage(), e);
        }

        // 同一行内按列号排序
        for (List<EmbeddedImage> images : result.values()) {
            images.sort(Comparator.comparingInt(EmbeddedImage::colIndex));
        }
        return result;
    }

    private static String buildKey(int sheetIndex, int rowIndex) {
        return sheetIndex + "," + rowIndex;
    }

    private static String resolveExtension(String suggestion) {
        if (suggestion == null) {
            return "jpg";
        }
        return switch (suggestion.toLowerCase()) {
            case "png" -> "png";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            case "tiff" -> "tiff";
            case "wmf" -> "wmf";
            case "emf" -> "emf";
            default -> "jpg";
        };
    }

    /**
     * 提取某行指定列的文本值，用于将图片关联到具体数据行。
     *
     * <p>注意：行号从 0 开始，且包含表头行。调用方应自行换算数据行索引。</p>
     */
    public static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    yield cell.getStringCellValue();
                }
            }
            default -> "";
        };
    }

    /**
     * Excel 内嵌图片数据。
     */
    public record EmbeddedImage(byte[] bytes, String extension, int colIndex, int rowIndex, int sheetIndex) {
    }
}
