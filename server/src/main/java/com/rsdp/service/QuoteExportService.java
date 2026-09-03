package com.rsdp.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.rsdp.dto.request.QuoteItemRequest;
import com.rsdp.dto.response.PriceChangeResponse;
import com.rsdp.dto.response.QuoteItemResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.QuoteSummaryResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 报价单导出服务。
 */
@Service
@RequiredArgsConstructor
public class QuoteExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QuoteService quoteService;

    /**
     * 根据 RSKU ID 及数量列表生成 Excel 报价单（成本核价口径）。
     *
     * @param quoteItems 报价单项请求列表
     * @return Excel 文件字节数组
     */
    public byte[] exportQuote(List<QuoteItemRequest> quoteItems) {
        return exportQuote(quoteItems, null);
    }

    /**
     * 根据 RSKU ID 及数量列表生成 Excel 报价单。
     *
     * <p>口径 cost：明细含「出厂价」列（维持原行为）；口径 sale：明细以「销售价」列计价，
     * 不包含任何成本列。</p>
     *
     * @param quoteItems 报价单项请求列表
     * @param mode       报价口径（可空，默认 cost）
     * @return Excel 文件字节数组
     */
    public byte[] exportQuote(List<QuoteItemRequest> quoteItems, String mode) {
        String resolvedMode = QuoteService.resolveMode(mode);
        boolean saleMode = QuoteService.MODE_SALE.equals(resolvedMode);
        QuoteResponse quote = quoteService.generateQuote(quoteItems, resolvedMode);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(outputStream)
            .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
            .build()) {

            WriteSheet itemSheet = saleMode
                ? EasyExcel.writerSheet("报价明细").head(SaleQuoteItemRow.class).build()
                : EasyExcel.writerSheet("报价明细").head(QuoteItemRow.class).build();
            writer.write(saleMode ? buildSaleItemRows(quote.getItems()) : buildItemRows(quote.getItems()), itemSheet);

            List<SummaryRow> summaryRows = buildSummaryRows(quote.getSummary(), quote.getPriceChanges(), resolvedMode);
            WriteSheet summarySheet = EasyExcel.writerSheet("汇总").head(SummaryRow.class).build();
            writer.write(summaryRows, summarySheet);
        }

        return outputStream.toByteArray();
    }

    private List<QuoteItemRow> buildItemRows(List<QuoteItemResponse> items) {
        List<QuoteItemRow> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            QuoteItemResponse item = items.get(i);
            QuoteItemRow row = new QuoteItemRow();
            row.setSeq(i + 1);
            row.setRspuId(item.getRspuId());
            // 名称列优先完整商品名（rspu_master.product_name），空则回退定位标签
            row.setRspuName(displayName(item));
            row.setRskuId(item.getRskuId());
            row.setFactoryCode(item.getFactoryCode());
            row.setFactoryName(item.getFactoryName());
            row.setFactorySku(item.getFactorySku());
            row.setFactoryPrice(formatPrice(item.getFactoryPrice()));
            row.setQuantity(item.getQuantity());
            row.setSubtotal(formatPrice(item.getSubtotal()));
            row.setPriceBand(item.getPriceBand());
            row.setMaterialDescription(item.getMaterialDescription());
            row.setLeadTimeDays(item.getLeadTimeDays());
            row.setMoq(item.getMoq());
            row.setWarrantyYears(item.getWarrantyYears());
            row.setShippingFrom(item.getShippingFrom());
            row.setDiffNotes(item.getDiffNotes());
            rows.add(row);
        }
        return rows;
    }

    private List<SaleQuoteItemRow> buildSaleItemRows(List<QuoteItemResponse> items) {
        List<SaleQuoteItemRow> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            QuoteItemResponse item = items.get(i);
            SaleQuoteItemRow row = new SaleQuoteItemRow();
            row.setSeq(i + 1);
            row.setRspuId(item.getRspuId());
            // 名称列优先完整商品名（rspu_master.product_name），空则回退定位标签
            row.setRspuName(displayName(item));
            row.setRskuId(item.getRskuId());
            row.setFactorySku(item.getFactorySku());
            row.setSalePrice(formatPrice(item.getSalePrice()));
            row.setQuantity(item.getQuantity());
            row.setSubtotal(formatPrice(item.getSubtotal()));
            row.setMaterialDescription(item.getMaterialDescription());
            row.setLeadTimeDays(item.getLeadTimeDays());
            row.setMoq(item.getMoq());
            row.setWarrantyYears(item.getWarrantyYears());
            row.setShippingFrom(item.getShippingFrom());
            row.setDiffNotes(item.getDiffNotes());
            rows.add(row);
        }
        return rows;
    }

    private List<SummaryRow> buildSummaryRows(QuoteSummaryResponse summary, List<PriceChangeResponse> priceChanges,
                                              String mode) {
        List<SummaryRow> rows = new ArrayList<>();
        rows.add(new SummaryRow("报价单生成时间", LocalDateTime.now().format(DATE_TIME_FORMATTER)));
        rows.add(new SummaryRow("报价口径",
            QuoteService.MODE_SALE.equals(mode) ? "销售报价（对客户）" : "成本核价（内部）"));
        rows.add(new SummaryRow("项数", String.valueOf(summary.getItemCount())));
        rows.add(new SummaryRow("总数量", String.valueOf(summary.getTotalQuantity())));
        if (!QuoteService.MODE_SALE.equals(mode)) {
            // 涉及工厂数为内部供应链信息，仅成本核价（内部）口径输出
            rows.add(new SummaryRow("涉及工厂数", String.valueOf(summary.getFactoryCount())));
        }
        rows.add(new SummaryRow("预估总价", formatPrice(summary.getTotalPrice())));
        rows.add(new SummaryRow("最大交期(天)", String.valueOf(summary.getMaxLeadTimeDays())));

        if (priceChanges != null && !priceChanges.isEmpty()) {
            rows.add(new SummaryRow("", ""));
            rows.add(new SummaryRow("价格变动提示", "以下 RSKU 价格自方案保存后发生变动"));
            for (PriceChangeResponse change : priceChanges) {
                rows.add(new SummaryRow(
                    change.getRspuName() + " (" + change.getRskuId() + ")",
                    "旧价: " + formatPrice(change.getOldPrice()) + ", 现价: " + formatPrice(change.getNewPrice())
                ));
            }
        }
        return rows;
    }

    /**
     * 报价项展示名称：完整商品名（product_name）优先，空则回退定位标签（rspuName）。
     *
     * @param item 报价项
     * @return 展示名称
     */
    private static String displayName(QuoteItemResponse item) {
        String productName = item.getProductName();
        return productName != null && !productName.isBlank() ? productName : item.getRspuName();
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "-";
        }
        return "¥" + price.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 报价明细行。
     */
    @Data
    public static class QuoteItemRow {

        @com.alibaba.excel.annotation.ExcelProperty("序号")
        private Integer seq;

        @com.alibaba.excel.annotation.ExcelProperty("RSPU ID")
        private String rspuId;

        @com.alibaba.excel.annotation.ExcelProperty("RSPU 名称")
        private String rspuName;

        @com.alibaba.excel.annotation.ExcelProperty("RSKU ID")
        private String rskuId;

        @com.alibaba.excel.annotation.ExcelProperty("工厂编码")
        private String factoryCode;

        @com.alibaba.excel.annotation.ExcelProperty("工厂名称")
        private String factoryName;

        @com.alibaba.excel.annotation.ExcelProperty("工厂 SKU")
        private String factorySku;

        @com.alibaba.excel.annotation.ExcelProperty("出厂价")
        private String factoryPrice;

        @com.alibaba.excel.annotation.ExcelProperty("数量")
        private Integer quantity;

        @com.alibaba.excel.annotation.ExcelProperty("小计")
        private String subtotal;

        @com.alibaba.excel.annotation.ExcelProperty("价格带")
        private String priceBand;

        @com.alibaba.excel.annotation.ExcelProperty("材质说明")
        private String materialDescription;

        @com.alibaba.excel.annotation.ExcelProperty("交期(天)")
        private Integer leadTimeDays;

        @com.alibaba.excel.annotation.ExcelProperty("MOQ")
        private Integer moq;

        @com.alibaba.excel.annotation.ExcelProperty("质保(年)")
        private Integer warrantyYears;

        @com.alibaba.excel.annotation.ExcelProperty("发货地")
        private String shippingFrom;

        @com.alibaba.excel.annotation.ExcelProperty("差异备注")
        private String diffNotes;
    }

    /**
     * 销售报价明细行（sale 口径：「销售价」替代「出厂价」，不含任何成本列与工厂信息列，工厂 SKU 以「型号」口径展示）。
     */
    @Data
    public static class SaleQuoteItemRow {

        @com.alibaba.excel.annotation.ExcelProperty("序号")
        private Integer seq;

        @com.alibaba.excel.annotation.ExcelProperty("RSPU ID")
        private String rspuId;

        @com.alibaba.excel.annotation.ExcelProperty("RSPU 名称")
        private String rspuName;

        @com.alibaba.excel.annotation.ExcelProperty("RSKU ID")
        private String rskuId;

        // 销售报价对客户导出：不含工厂编码/工厂名称（防供应链信息泄露），工厂 SKU 以「型号」口径展示

        @com.alibaba.excel.annotation.ExcelProperty("型号")
        private String factorySku;

        @com.alibaba.excel.annotation.ExcelProperty("销售价")
        private String salePrice;

        @com.alibaba.excel.annotation.ExcelProperty("数量")
        private Integer quantity;

        @com.alibaba.excel.annotation.ExcelProperty("小计")
        private String subtotal;

        @com.alibaba.excel.annotation.ExcelProperty("材质说明")
        private String materialDescription;

        @com.alibaba.excel.annotation.ExcelProperty("交期(天)")
        private Integer leadTimeDays;

        @com.alibaba.excel.annotation.ExcelProperty("MOQ")
        private Integer moq;

        @com.alibaba.excel.annotation.ExcelProperty("质保(年)")
        private Integer warrantyYears;

        @com.alibaba.excel.annotation.ExcelProperty("发货地")
        private String shippingFrom;

        @com.alibaba.excel.annotation.ExcelProperty("差异备注")
        private String diffNotes;
    }

    /**
     * 汇总信息行。
     */
    @Data
    public static class SummaryRow {

        @com.alibaba.excel.annotation.ExcelProperty("项目")
        private String key;

        @com.alibaba.excel.annotation.ExcelProperty("内容")
        private String value;

        public SummaryRow(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
