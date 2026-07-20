package com.rsdp.service;

import com.rsdp.mapper.OrderNoCounterMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link OrderNoGenerator} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class OrderNoGeneratorTest {

    @Mock
    private OrderNoCounterMapper orderNoCounterMapper;

    @InjectMocks
    private OrderNoGenerator orderNoGenerator;

    @Test
    void generateShouldFormatSequenceWithThreeDigits() {
        when(orderNoCounterMapper.allocateSequence("20260720")).thenReturn(1L);

        String orderNo = orderNoGenerator.generate();

        assertThat(orderNo).isEqualTo("DO-20260720-001");
    }

    @Test
    void generateShouldIncrementSequenceBasedOnCounter() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        when(orderNoCounterMapper.allocateSequence(datePart)).thenReturn(10L);

        String orderNo = orderNoGenerator.generate();

        assertThat(orderNo).isEqualTo("DO-" + datePart + "-010");
    }
}
