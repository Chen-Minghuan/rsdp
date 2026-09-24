package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.KnowledgeProductAttribute;
import com.rsdp.mapper.KnowledgeProductAttributeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeProductAttributeServiceTest {

    @Test
    void listActiveByCategory_shouldIncludeGenericAndNormalizedCategoryScope() {
        KnowledgeProductAttributeMapper mapper = mock(KnowledgeProductAttributeMapper.class);
        KnowledgeProductAttribute expected = new KnowledgeProductAttribute();
        expected.setAttributeId("ATTR-DK-TABLETOP_HEIGHT_MM");
        when(mapper.selectList(any())).thenReturn(List.of(expected));

        KnowledgeProductAttributeService service = new KnowledgeProductAttributeService(mapper);

        assertThat(service.listActiveByCategory(" dk ")).containsExactly(expected);
        ArgumentCaptor<QueryWrapper<KnowledgeProductAttribute>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertThat(sql).contains("status", "category_code", "product_type_code");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue("DK");
    }

    @Test
    void listActiveByCategory_blankCategory_shouldOnlyRequestGenericScope() {
        KnowledgeProductAttributeMapper mapper = mock(KnowledgeProductAttributeMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        KnowledgeProductAttributeService service = new KnowledgeProductAttributeService(mapper);

        service.listActiveByCategory("  ");

        ArgumentCaptor<QueryWrapper<KnowledgeProductAttribute>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).doesNotContain("category_code =");
    }
}
