package com.rsdp.service;

import com.rsdp.dto.request.QuoteItemRequest;
import com.rsdp.dto.response.QuoteItemResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.QuoteSummaryResponse;
import com.rsdp.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link QuoteExportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QuoteExportServiceTest {

    @Mock
    private QuoteService quoteService;

    @InjectMocks
    private QuoteExportService quoteExportService;

    @Test
    void exportQuote_shouldReturnExcelBytes() throws Exception {
        QuoteItemResponse item = new QuoteItemResponse();
        item.setRspuId("RSPU-001");
        item.setRspuName("中古风");
        item.setProductName("像素沙发");
        item.setRskuId("RSKU-001");
        item.setFactoryCode("F001");
        item.setFactoryName("测试工厂");
        item.setFactoryPrice(new BigDecimal("2500"));
        item.setQuantity(3);
        item.setSubtotal(new BigDecimal("7500"));
        item.setSpaceTagName("客厅");
        item.setLeadTimeDays(25);
        item.setMoq(10);

        QuoteSummaryResponse summary = new QuoteSummaryResponse();
        summary.setTotalPrice(new BigDecimal("7500"));
        summary.setItemCount(1);
        summary.setTotalQuantity(3);
        summary.setFactoryCount(1);
        summary.setMaxLeadTimeDays(25);

        QuoteResponse quote = new QuoteResponse();
        quote.setItems(List.of(item));
        quote.setSummary(summary);

        List<QuoteItemRequest> request = List.of(req("RSKU-001", 3));
        when(quoteService.generateQuote(request, "cost")).thenReturn(quote);

        byte[] content = quoteExportService.exportQuote(request);

        assertThat(content).isNotEmpty();
        // Excel .xlsx 文件本质上是 ZIP 文件，起始字节应为 "PK"
        assertThat(content).startsWith((byte) 0x50, (byte) 0x4B);
        // 「RSPU 名称」列值优先完整商品名称
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("报价明细");
            int nameCol = columnIndex(sheet.getRow(0), "RSPU 名称");
            assertThat(sheet.getRow(1).getCell(nameCol).getStringCellValue()).isEqualTo("像素沙发");
            // 方案语境：「空间」列带显示名
            int spaceCol = columnIndex(sheet.getRow(0), "空间");
            assertThat(sheet.getRow(1).getCell(spaceCol).getStringCellValue()).isEqualTo("客厅");
        }
    }

    @Test
    void exportQuote_saleMode_shouldUseSalePriceColumnWithoutCost() throws Exception {
        QuoteItemResponse item = new QuoteItemResponse();
        item.setRspuId("RSPU-001");
        item.setRspuName("中古风");
        item.setProductName("像素沙发");
        item.setRskuId("RSKU-001");
        item.setFactoryCode("F001");
        item.setFactoryName("测试工厂");
        item.setSalePrice(new BigDecimal("6000"));
        item.setQuantity(3);
        item.setSubtotal(new BigDecimal("18000.00"));
        item.setSpaceTagName("客厅");
        item.setLeadTimeDays(25);
        item.setMoq(10);

        QuoteSummaryResponse summary = new QuoteSummaryResponse();
        summary.setTotalPrice(new BigDecimal("18000.00"));
        summary.setItemCount(1);
        summary.setTotalQuantity(3);
        summary.setFactoryCount(1);
        summary.setMaxLeadTimeDays(25);

        QuoteResponse quote = new QuoteResponse();
        quote.setItems(List.of(item));
        quote.setSummary(summary);

        List<QuoteItemRequest> request = List.of(req("RSKU-001", 3));
        when(quoteService.generateQuote(request, "sale")).thenReturn(quote);

        byte[] content = quoteExportService.exportQuote(request, "sale");

        assertThat(content).startsWith((byte) 0x50, (byte) 0x4B);
        // 明细表头：sale 口径为「销售价」且不出现「出厂价」成本列
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(content))) {
            var headerRow = workbook.getSheet("报价明细").getRow(0);
            List<String> headers = new ArrayList<>();
            headerRow.forEach(cell -> headers.add(cell.getStringCellValue()));
            assertThat(headers).contains("销售价");
            assertThat(headers).doesNotContain("出厂价");
            // 「RSPU 名称」列值优先完整商品名称
            int nameCol = columnIndex(workbook.getSheet("报价明细").getRow(0), "RSPU 名称");
            assertThat(workbook.getSheet("报价明细").getRow(1).getCell(nameCol).getStringCellValue())
                .isEqualTo("像素沙发");
            // 销售版同样带「空间」列（空间非敏感信息）
            int spaceCol = columnIndex(workbook.getSheet("报价明细").getRow(0), "空间");
            assertThat(workbook.getSheet("报价明细").getRow(1).getCell(spaceCol).getStringCellValue())
                .isEqualTo("客厅");
            // 销售报价对客户导出：不出现工厂编码/工厂名称列，工厂 SKU 以「型号」口径展示；汇总不含"涉及工厂数"
            assertThat(headers).doesNotContain("工厂编码", "工厂名称", "工厂 SKU");
            assertThat(headers).contains("型号");
            // 汇总 sheet 标注报价口径
            var summarySheet = workbook.getSheet("汇总");
            boolean hasModeLabel = false;
            boolean hasFactoryCountRow = false;
            for (var row : summarySheet) {
                String label = row.getCell(0) != null ? row.getCell(0).getStringCellValue() : null;
                if ("报价口径".equals(label)) {
                    hasModeLabel = true;
                    assertThat(row.getCell(1).getStringCellValue()).contains("销售报价");
                }
                if ("涉及工厂数".equals(label)) {
                    hasFactoryCountRow = true;
                }
            }
            assertThat(hasModeLabel).isTrue();
            // 销售报价（对客户）汇总不得包含"涉及工厂数"（内部供应链信息）
            assertThat(hasFactoryCountRow).isFalse();
        }
    }

    @Test
    void exportQuote_shouldPropagateEmptyListException() {
        when(quoteService.generateQuote(List.of(), "cost")).thenThrow(new BusinessException("请选择至少一个 RSKU"));

        assertThatThrownBy(() -> quoteExportService.exportQuote(List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("至少一个");
    }

    private QuoteItemRequest req(String rskuId, int quantity) {
        QuoteItemRequest r = new QuoteItemRequest();
        r.setRskuId(rskuId);
        r.setQuantity(quantity);
        return r;
    }

    /** 按表头文本定位列索引。 */
    private static int columnIndex(org.apache.poi.ss.usermodel.Row headerRow, String header) {
        for (var cell : headerRow) {
            if (header.equals(cell.getStringCellValue())) {
                return cell.getColumnIndex();
            }
        }
        throw new IllegalStateException("表头不存在: " + header);
    }
}
