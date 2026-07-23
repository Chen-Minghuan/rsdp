package com.rsdp.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.rsdp.dto.response.OrderDetailResponse;
import com.rsdp.dto.response.OrderItemResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单明细导出服务：{orderNo}-订单明细.xlsx（明细 + 汇总双 sheet）。
 *
 * <p>归属校验与生效价（改价优先）计算复用 {@link OrderService#detail(String)}。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderService orderService;

    /**
     * 导出文件（内容 + 文件名）。
     *
     * @param content  Excel 字节
     * @param fileName 文件名（含 .xlsx 后缀）
     */
    public record OrderExportFile(byte[] content, String fileName) {
    }

    /**
     * 导出订单明细 Excel。
     *
     * @param orderId 订单 ID
     * @return 导出文件
     */
    public OrderExportFile export(String orderId) {
        OrderDetailResponse detail = orderService.detail(orderId);

        List<OrderItemRow> itemRows = new ArrayList<>();
        List<OrderItemResponse> items = detail.getItems() != null ? detail.getItems() : List.of();
        for (int i = 0; i < items.size(); i++) {
            OrderItemResponse item = items.get(i);
            OrderItemRow row = new OrderItemRow();
            row.setSeq(i + 1);
            row.setProductName(item.getProductName());
            row.setModel(item.getModel());
            row.setRspuId(item.getRspuId());
            row.setFactoryCode(item.getFactoryCode());
            row.setQuantity(item.getQuantity());
            row.setOriginalPrice(formatPrice(item.getOriginalPrice()));
            row.setFinalPrice(formatPrice(item.getFinalPrice()));
            row.setAdjustPrice(item.getAdjustPrice() != null ? formatPrice(item.getAdjustPrice()) : "-");
            row.setEffectivePrice(formatPrice(item.getEffectivePrice()));
            row.setSubtotal(formatPrice(item.getSubtotal()));
            itemRows.add(row);
        }

        List<SummaryRow> summaryRows = buildSummaryRows(detail);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(outputStream)
            .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
            .build()) {
            WriteSheet itemSheet = EasyExcel.writerSheet("订单明细").head(OrderItemRow.class).build();
            writer.write(itemRows, itemSheet);
            WriteSheet summarySheet = EasyExcel.writerSheet("汇总").head(SummaryRow.class).build();
            writer.write(summaryRows, summarySheet);
        }

        return new OrderExportFile(outputStream.toByteArray(), detail.getOrderNo() + "-订单明细.xlsx");
    }

    private List<SummaryRow> buildSummaryRows(OrderDetailResponse detail) {
        List<SummaryRow> rows = new ArrayList<>();
        rows.add(new SummaryRow("订单编号", detail.getOrderNo()));
        rows.add(new SummaryRow("订单状态", detail.getStatus()));
        rows.add(new SummaryRow("收件人", nullToDash(detail.getReceiverName())));
        rows.add(new SummaryRow("联系电话", nullToDash(detail.getReceiverPhone())));
        rows.add(new SummaryRow("收件地址",
            (nullToDash(detail.getReceiverArea()) + " " + nullToDash(detail.getReceiverAddress())).trim()));
        rows.add(new SummaryRow("明细项数", String.valueOf(detail.getItemCount())));
        rows.add(new SummaryRow("原价合计", formatPrice(detail.getOriginalTotalPrice())));
        rows.add(new SummaryRow("折扣率", detail.getPriceRate() != null ? detail.getPriceRate().toPlainString() : "-"));
        rows.add(new SummaryRow("到手价合计", formatPrice(detail.getFinalTotalPrice())));
        rows.add(new SummaryRow("预计交期(天)", detail.getExpectedLeadTime() != null ? String.valueOf(detail.getExpectedLeadTime()) : "-"));
        rows.add(new SummaryRow("备注", nullToDash(detail.getRemark())));
        rows.add(new SummaryRow("导出时间", LocalDateTime.now().format(DATE_TIME_FORMATTER)));
        return rows;
    }

    private String nullToDash(String value) {
        return value != null && !value.isBlank() ? value : "-";
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "-";
        }
        return "¥" + price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 订单明细行。
     */
    @Data
    public static class OrderItemRow {

        @ExcelProperty("序号")
        private Integer seq;

        @ExcelProperty("产品名称")
        private String productName;

        @ExcelProperty("型号")
        private String model;

        @ExcelProperty("RSPU ID")
        private String rspuId;

        @ExcelProperty("工厂编码")
        private String factoryCode;

        @ExcelProperty("数量")
        private Integer quantity;

        @ExcelProperty("原单价")
        private String originalPrice;

        @ExcelProperty("到手单价")
        private String finalPrice;

        @ExcelProperty("改价")
        private String adjustPrice;

        @ExcelProperty("生效单价")
        private String effectivePrice;

        @ExcelProperty("小计")
        private String subtotal;
    }

    /**
     * 汇总信息行。
     */
    @Data
    public static class SummaryRow {

        @ExcelProperty("项目")
        private String key;

        @ExcelProperty("内容")
        private String value;

        public SummaryRow(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
