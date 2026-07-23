package com.rsdp.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.ShapeContainer;
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
import java.util.Set;

/**
 * Excel 内嵌图片提取工具。
 *
 * <p>遍历工作簿全部 Sheet（多 Sheet 文件按批次的 sheet_index 取对应 sheet 的图），
 * 根据 ClientAnchor 定位到行，同一行内按列号排序，第一列的图片作为主图候选。
 * 结果 key 格式为 "sheetIndex,rowIndex"。</p>
 */
@Slf4j
public final class ExcelImageExtractor {

    private static final int MAX_SHEETS = 50;
    private static final int MAX_PICTURES = 1000;
    private static final long MAX_TOTAL_IMAGE_BYTES = 200 * 1024 * 1024L; // 200 MB
    /** 浏览器无法渲染、提取阶段直接跳过的非 web 图片格式 */
    private static final Set<String> NON_WEB_IMAGE_FORMATS = Set.of("emf", "wmf", "tiff");

    static {
        // zip bomb 加固（P2-14）：POI 的 ZipSecureFile 是 JVM 全局静态设置，
        // 在本类（工程内唯一显式 WorkbookFactory 调用点）加载时统一收紧，最小侵入。
        // - minInflateRatio 0.005：压缩包膨胀率下限，防止高压缩比炸弹（业务文件含大量
        //   不可压缩图片，正常膨胀率远高于该阈值）；
        // - maxEntrySize 512MB：单个 zip 条目解压上限（业务上限：上传文件 200MB，
        //   图片总配额默认 500MB，单条目 512MB 足够覆盖正常文件）。
        ZipSecureFile.setMinInflateRatio(0.005d);
        ZipSecureFile.setMaxEntrySize(512L * 1024 * 1024);
    }

    private ExcelImageExtractor() {
    }

    /**
     * 提取 Excel 全部内嵌图片（默认 200MB 总量上限，超限截断保留已提取部分）。
     *
     * @param file Excel 文件
     * @return 按 sheetIndex+rowIndex 分组的内嵌图片
     * @throws IOException 文件损坏等整体性失败
     */
    public static Map<String, List<EmbeddedImage>> extract(MultipartFile file) throws IOException {
        return extractWithLimit(file, MAX_TOTAL_IMAGE_BYTES).images();
    }

    /**
     * 提取 Excel 全部内嵌图片，按 sheet+物理行号分组。
     *
     * <p>图片总量超过 maxTotalImageBytes 时停止提取剩余图片、保留已提取部分（截断），
     * 通过 truncated 回传标记（可为 null 表示不关心）；不再整体抛异常导致全量丢图。</p>
     *
     * @param file               Excel 文件
     * @param maxTotalImageBytes 图片总量上限（字节）
     * @param truncated          可选的输出标记：truncated[0]=true 表示发生了截断
     * @return key 为 "sheetIndex,rowIndex" 的图片分组
     * @throws IOException 文件损坏等整体性失败
     */
    public static Map<String, List<EmbeddedImage>> extract(MultipartFile file, long maxTotalImageBytes,
                                                           boolean[] truncated) throws IOException {
        ExtractionResult result = extractWithLimit(file, maxTotalImageBytes);
        if (truncated != null && truncated.length > 0) {
            truncated[0] = result.truncated();
        }
        return result.images();
    }

    /**
     * 提取 Excel 内嵌图片（截断时回传具体原因）。
     *
     * <p>与 {@link #extract(MultipartFile, long, boolean[])} 同语义，但截断原因区分
     * 「总字节超限」与「总张数超限」，且不依赖可选输出参数——单参调用同样受配额约束。</p>
     *
     * @param file               Excel 文件
     * @param maxTotalImageBytes 图片总量上限（字节）
     * @return 提取结果（图片分组 + 截断标记 + 截断原因）
     * @throws IOException 文件损坏等整体性失败
     */
    public static ExtractionResult extractWithLimit(MultipartFile file, long maxTotalImageBytes) throws IOException {
        if (file == null || file.isEmpty()) {
            return new ExtractionResult(Collections.emptyMap(), false, null);
        }

        ExtractionState state = new ExtractionState();
        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {

            if (workbook.getNumberOfSheets() > MAX_SHEETS) {
                throw new IOException("Excel 文件 Sheet 数量超过上限 " + MAX_SHEETS);
            }

            // 遍历全部 Sheet：多 Sheet 文件的消费侧（ExcelAiImportService）按批次 sheet_index
            // 以 "sheetIndex,row" key 取本 sheet 的图；配额（字节/张数）全工作簿共享
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Drawing<?> drawing = sheet.getDrawingPatriarch();
                if (drawing == null) {
                    continue;
                }
                for (Shape shape : drawing) {
                    // 局部截断标记无条件守卫（不依赖可选输出参数）
                    if (state.truncationReason != null) {
                        break;
                    }
                    collectFromShape(shape, maxTotalImageBytes, state, sheetIndex);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 整体性失败（文件损坏等）向上抛，由调用方记录告警
            throw new IOException("解析 Excel 内嵌图片失败: " + e.getMessage(), e);
        }

        // 同一行内按列号排序
        for (List<EmbeddedImage> images : state.result.values()) {
            images.sort(Comparator.comparingInt(EmbeddedImage::colIndex));
        }
        return new ExtractionResult(state.result, state.truncationReason != null, state.truncationReason);
    }

    /**
     * 从单个 Shape 收集图片；ShapeContainer（如编组）递归展开。
     *
     * <p>注意：POI 读回编组内图片时 ClientAnchor 通常为 null（锚点存在编组子坐标系
     * xfrm 中，usermodel API 不解析），此时由下方的空锚点守卫跳过，不影响其他图片。</p>
     */
    private static void collectFromShape(Shape shape, long maxTotalImageBytes, ExtractionState state,
                                         int sheetIndex) {
        if (state.truncationReason != null) {
            return;
        }
        if (shape instanceof ShapeContainer container && !(shape instanceof Picture)) {
            // ShapeContainer 是原始 Iterable（元素静态类型 Object），逐个判断后递归
            for (Object child : container) {
                if (child instanceof Shape childShape) {
                    collectFromShape(childShape, maxTotalImageBytes, state, sheetIndex);
                }
            }
            return;
        }
        if (!(shape instanceof Picture picture)) {
            return;
        }
        try {
            ClientAnchor anchor = picture.getClientAnchor();
            if (anchor == null) {
                return;
            }
            // 负锚点行无法对齐数据行，跳过而非错误归到第 0 行
            int rowIndex = anchor.getRow1();
            if (rowIndex < 0) {
                return;
            }

            PictureData pictureData = picture.getPictureData();
            if (pictureData == null || pictureData.getData() == null || pictureData.getData().length == 0) {
                return;
            }

            // 非 web 图片格式（EMF/WMF/TIFF）浏览器无法渲染：提取阶段直接跳过，
            // 不占用字节/张数配额（服务层仍保留同类跳过逻辑作为双保险）
            String extension = resolveExtension(pictureData.suggestFileExtension());
            if (NON_WEB_IMAGE_FORMATS.contains(extension)) {
                return;
            }

            if (++state.pictureCount > MAX_PICTURES) {
                log.warn("内嵌图片数量超过上限 {}，截断剩余图片", MAX_PICTURES);
                state.truncationReason = "内嵌图片数量超过上限 " + MAX_PICTURES + " 张";
                return;
            }
            state.totalImageBytes += pictureData.getData().length;
            if (state.totalImageBytes > maxTotalImageBytes) {
                log.warn("内嵌图片总大小超过上限 {} 字节，截断剩余图片", maxTotalImageBytes);
                state.truncationReason = "内嵌图片总大小超过上限 " + (maxTotalImageBytes / 1024 / 1024) + "MB";
                return;
            }

            int colIndex = Math.max(0, anchor.getCol1());
            String key = buildKey(sheetIndex, rowIndex);

            EmbeddedImage image = new EmbeddedImage(
                pictureData.getData(),
                extension,
                colIndex,
                rowIndex,
                sheetIndex
            );
            state.result.computeIfAbsent(key, k -> new ArrayList<>()).add(image);
        } catch (Exception e) {
            // 单张图片损坏/无法解析时跳过，不影响其余图片提取
            log.warn("跳过无法解析的内嵌图片", e);
        }
    }

    /**
     * 提取过程的可变状态（结果集 + 配额计数 + 截断原因）。
     */
    private static final class ExtractionState {
        private final Map<String, List<EmbeddedImage>> result = new HashMap<>();
        private long totalImageBytes;
        private int pictureCount;
        private String truncationReason;
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

    /**
     * 图片提取结果：分组图片 + 截断标记 + 截断原因（区分总字节超限与总张数超限）。
     */
    public record ExtractionResult(Map<String, List<EmbeddedImage>> images, boolean truncated,
                                   String truncationReason) {
    }
}
