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
     * 根据 RSKU ID 及数量列表生成 Excel 报价单。
     *
     * @param quoteItems 报价单项请求列表
     * @return Excel 文件字节数组
     */
    public byte[] exportQuote(List<QuoteItemRequest> quoteItems) {
        QuoteResponse quote = quoteService.generateQuote(quoteItems);

        List<QuoteItemRow> itemRows = buildItemRows(quote.getItems());
        List<SummaryRow> summaryRows = buildSummaryRows(quote.getSummary(), quote.getPriceChanges());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(outputStream)
            .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
            .build()) {

            WriteSheet itemSheet = EasyExcel.writerSheet("报价明细").head(QuoteItemRow.class).build();
            writer.write(itemRows, itemSheet);

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
            row.setRspuName(item.getRspuName());
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

    private List<SummaryRow> buildSummaryRows(QuoteSummaryResponse summary, List<PriceChangeResponse> priceChanges) {
        List<SummaryRow> rows = new ArrayList<>();
        rows.add(new SummaryRow("报价单生成时间", LocalDateTime.now().format(DATE_TIME_FORMATTER)));
        rows.add(new SummaryRow("项数", String.valueOf(summary.getItemCount())));
        rows.add(new SummaryRow("总数量", String.valueOf(summary.getTotalQuantity())));
        rows.add(new SummaryRow("涉及工厂数", String.valueOf(summary.getFactoryCount())));
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
