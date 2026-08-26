package com.rsdp.util;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.write.handler.CellWriteHandler;
import com.alibaba.excel.write.handler.context.CellWriteHandlerContext;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.rsdp.dto.excel.ProductImportRow;
import org.apache.poi.ss.usermodel.IndexedColors;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 产品（RSPU）批量导入 Excel 模板生成器。
 *
 * <p>模板含两个 Sheet：
 * <ul>
 *   <li>Sheet1「产品导入模板」：仅表头（不写示例数据行，防止误导入），表头文字通过反射读取
 *   {@link ProductImportRow} 的 {@code @ExcelProperty} 值生成，保证与导入解析口径一致；
 *   必填列（品类码）红色加粗，建议列橙色加粗，其余正常。</li>
 *   <li>Sheet2「填写说明」：图例 + 逐列填写要求/说明/示例值。</li>
 * </ul>
 */
public final class ProductImportTemplateBuilder {

    /** Sheet1 名称 */
    public static final String TEMPLATE_SHEET_NAME = "产品导入模板";
    /** Sheet2 名称 */
    public static final String INSTRUCTION_SHEET_NAME = "填写说明";

    /** 必填列（缺失即行报错） */
    private static final Set<String> REQUIRED_HEADS = Set.of("品类码");

    /** 建议填写列（缺失不报错，但会导致业务编码/官网价格等缺失） */
    private static final Set<String> RECOMMENDED_HEADS = Set.of(
        "定位标签", "商品名称", "材质码", "材质原文", "主图URL", "零售参考价", "尺寸码", "尺寸原文");

    private ProductImportTemplateBuilder() {
    }

    /**
     * 反射读取 {@link ProductImportRow} 的 {@code @ExcelProperty} 值，按字段声明顺序返回模板表头名。
     *
     * @return 表头名列表（与导入解析口径一致）
     */
    public static List<String> headerNames() {
        List<String> heads = new ArrayList<>();
        for (Field field : ProductImportRow.class.getDeclaredFields()) {
            ExcelProperty property = field.getAnnotation(ExcelProperty.class);
            if (property == null) {
                continue;
            }
            String[] value = property.value();
            heads.add(value.length > 0 && !value[0].isBlank() ? value[0] : field.getName());
        }
        return heads;
    }

    /**
     * 将导入模板写入输出流。
     *
     * @param outputStream 目标输出流（方法内不关闭）
     */
    public static void write(OutputStream outputStream) {
        List<List<String>> head = headerNames().stream()
            .<List<String>>map(List::of)
            .toList();

        var excelWriter = EasyExcel.write(outputStream)
            .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
            .registerWriteHandler(new TemplateHeaderStyleHandler())
            .build();
        try {
            // Sheet1：仅表头，不写示例数据行
            WriteSheet templateSheet = EasyExcel.writerSheet(0, TEMPLATE_SHEET_NAME)
                .head(head)
                .build();
            excelWriter.write(List.of(), templateSheet);

            // Sheet2：填写说明
            WriteSheet instructionSheet = EasyExcel.writerSheet(1, INSTRUCTION_SHEET_NAME)
                .needHead(false)
                .build();
            excelWriter.write(instructionRows(), instructionSheet);
        } finally {
            excelWriter.finish();
        }
    }

    /**
     * 构建「填写说明」Sheet 的内容行（图例 + 逐列表格）。
     *
     * @return 说明行（每行为一格字符串列表）
     */
    static List<List<String>> instructionRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("图例", "红色加粗 = 必填列（缺失该行报错）；橙色加粗 = 建议填写列（缺失不报错但影响业务编码/官网展示）；其余 = 可选列"));
        rows.add(List.of("重要", "表头文字请勿修改（导入按表头名解析列）；多值字段用英文逗号分隔"));
        rows.add(List.of("更新模式", "updateIfExists=true 时，空单元格不覆盖已有数据"));
        rows.add(List.of());
        rows.add(List.of("列名", "填写要求", "说明", "示例值"));
        rows.add(List.of("RSPU ID", "可选", "已有产品的 UUID 主键，用于更新定位；留空则新建。长度≤64", "RSPU-0f3a2c…"));
        rows.add(List.of("外部编码", "可选", "工厂/外部系统自有编码，作为查重键之一。长度≤64", "A004-SF-001"));
        rows.add(List.of("品类码", "必填", "必须存在于 category 字典（如 SF 沙发 / TB 桌 / BS 吧椅）。长度≤16，缺失或不存在该行报错", "SF"));
        rows.add(List.of("定位标签", "建议", "style 字典码（如 MC 现代简约）；缺失则无 rspu_code 业务编码。长度≤64", "MC"));
        rows.add(List.of("商品名称", "建议", "产品库列表展示名称", "云朵三人位沙发"));
        rows.add(List.of("主色", "可选", "产品主色名称。长度≤64", "米白"));
        rows.add(List.of("材质标签", "可选", "多值英文逗号分隔", "头层牛皮,实木框架"));
        rows.add(List.of("场景标签", "可选", "scene 字典码，多值英文逗号分隔", "LR,BR"));
        rows.add(List.of("产品等级", "可选", "factory_level 字典码。长度≤16", "A"));
        rows.add(List.of("保修年限", "可选", "整数，0~100", "3"));
        rows.add(List.of("参考价格带", "可选", "low/mid/high 之一（不区分大小写）", "mid"));
        rows.add(List.of("六维标签", "可选", "合法 JSON 字符串", "{\"style\":[\"MC\"]}"));
        rows.add(List.of("关键规格", "可选", "合法 JSON 字符串", "{\"frame\":\"实木\"}"));
        rows.add(List.of("主图URL", "建议", "http(s) 图片地址，导入时下载并设为主图", "https://example.com/a.jpg"));
        rows.add(List.of("详情图URLs", "可选", "多值英文逗号分隔的图片地址", "https://example.com/b.jpg,https://example.com/c.jpg"));
        rows.add(List.of("变体显示名称", "可选", "默认变体名称。长度≤128", "三人位/米白"));
        rows.add(List.of("尺寸码", "建议", "size 字典码；未识别时降级为原文保留。长度≤64", "M"));
        rows.add(List.of("尺寸原文", "建议", "工厂尺寸/规格原文（码未识别时使用），与尺寸码可二选一", "2200×950×850"));
        rows.add(List.of("颜色码", "可选", "color 字典码；未识别时降级为原文保留。长度≤64", "WH"));
        rows.add(List.of("颜色原文", "可选", "工厂颜色原文（码未识别时使用）", "珍珠白"));
        rows.add(List.of("材质码", "建议", "material 字典码；未识别时降级为原文保留。长度≤128；材质码与材质原文均缺失则无 rsku_code", "PE"));
        rows.add(List.of("材质原文", "建议", "工厂材质原文（码未识别时使用）", "头层牛皮"));
        rows.add(List.of("变体参考价格带", "可选", "low/mid/high 之一（不区分大小写）", "mid"));
        rows.add(List.of("变体产品等级", "可选", "factory_level 字典码。长度≤8", "A"));
        rows.add(List.of("交期天数", "可选", "整数天数", "15"));
        rows.add(List.of("描述/配置说明", "可选", "长文本描述原文", "含两个抱枕，脚垫可拆"));
        rows.add(List.of("零售参考价", "建议", "数字（元）；缺失则官网无价展示", "3999"));
        return rows;
    }

    /**
     * Sheet1 表头样式：必填列红色加粗，建议列橙色加粗，其余列保持默认。
     */
    private static class TemplateHeaderStyleHandler implements CellWriteHandler {

        @Override
        public void afterCellDispose(CellWriteHandlerContext context) {
            if (!Boolean.TRUE.equals(context.getHead())
                || context.getWriteSheetHolder().getSheetNo() != 0) {
                return;
            }
            String headName = context.getCell().getStringCellValue();
            if (REQUIRED_HEADS.contains(headName)) {
                applyStyle(context, IndexedColors.RED.getIndex());
            } else if (RECOMMENDED_HEADS.contains(headName)) {
                applyStyle(context, IndexedColors.ORANGE.getIndex());
            }
        }

        private void applyStyle(CellWriteHandlerContext context, short colorIndex) {
            WriteCellStyle cellStyle = context.getFirstCellData().getOrCreateStyle();
            WriteFont font = new WriteFont();
            font.setBold(true);
            font.setColor(colorIndex);
            cellStyle.setWriteFont(font);
        }
    }
}
