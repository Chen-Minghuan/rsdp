package com.rsdp.service;

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
    void exportQuote_shouldReturnExcelBytes() {
        QuoteItemResponse item = new QuoteItemResponse();
        item.setRspuId("RSPU-001");
        item.setRspuName("中古风沙发");
        item.setRskuId("RSKU-001");
        item.setFactoryCode("F001");
        item.setFactoryName("测试工厂");
        item.setFactoryPrice(new BigDecimal("2500"));
        item.setLeadTimeDays(25);
        item.setMoq(10);

        QuoteSummaryResponse summary = new QuoteSummaryResponse();
        summary.setTotalPrice(new BigDecimal("2500"));
        summary.setItemCount(1);
        summary.setFactoryCount(1);
        summary.setMaxLeadTimeDays(25);

        QuoteResponse quote = new QuoteResponse();
        quote.setItems(List.of(item));
        quote.setSummary(summary);

        when(quoteService.generateQuote(List.of("RSKU-001"))).thenReturn(quote);

        byte[] content = quoteExportService.exportQuote(List.of("RSKU-001"));

        assertThat(content).isNotEmpty();
        // Excel .xlsx 文件本质上是 ZIP 文件，起始字节应为 "PK"
        assertThat(content).startsWith((byte) 0x50, (byte) 0x4B);
    }

    @Test
    void exportQuote_shouldPropagateEmptyListException() {
        when(quoteService.generateQuote(List.of())).thenThrow(new BusinessException("请选择至少一个 RSKU"));

        assertThatThrownBy(() -> quoteExportService.exportQuote(List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("至少一个");
    }
}
