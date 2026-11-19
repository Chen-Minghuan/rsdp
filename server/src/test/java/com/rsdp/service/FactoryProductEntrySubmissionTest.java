package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.FactoryProductEntryRequest;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

class FactoryProductEntrySubmissionTest {
    private final ProductService products = mock(ProductService.class);
    private final SupplierIdentityResolutionService identities = mock(SupplierIdentityResolutionService.class);
    private final SupplierProductRefService refs = mock(SupplierProductRefService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FactoryProductEntrySubmission submission = new FactoryProductEntrySubmission(
        products, identities, refs, jdbc, new ObjectMapper());

    @Test
    void requiresRequestKeyAndProductIdentityBeforeWriting() {
        var request = new FactoryProductEntryRequest();
        request.setFactoryCode("B001");
        request.setProductName("办公椅");
        request.setExternalCode("CHAIR-01");

        try (var session = mockStatic(SecurityOperatorContext.class)) {
            session.when(SecurityOperatorContext::currentUserId).thenReturn("USER-1");
            assertThatThrownBy(() -> submission.create(request, List.of(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("请求编号");
            request.setExternalCode("　");
            assertThatThrownBy(() -> submission.create(request, List.of(), "request-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("厂内型号与商品名称");
            request.setExternalCode("CHAIR-01");
            request.setProductName(" ");
            assertThatThrownBy(() -> submission.create(request, List.of(), "request-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("厂内型号与商品名称");
        }
        verifyNoInteractions(jdbc, refs, identities);
    }
}
