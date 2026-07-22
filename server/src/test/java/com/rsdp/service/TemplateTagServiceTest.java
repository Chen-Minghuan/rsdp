package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.TemplateTagRequest;
import com.rsdp.dto.response.TemplateTagResponse;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.TemplateTag;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.mapper.TemplateTagMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TemplateTagService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class TemplateTagServiceTest {

    @Mock
    private TemplateTagMapper templateTagMapper;

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TemplateTagService templateTagService;

    private TemplateTag tag(String id, String name, boolean enabled) {
        TemplateTag tag = new TemplateTag();
        tag.setTagId(id);
        tag.setTagName(name);
        tag.setSortOrder(0);
        tag.setEnabled(enabled);
        return tag;
    }

    private TemplateTagRequest request(String name) {
        TemplateTagRequest request = new TemplateTagRequest();
        request.setTagName(name);
        return request;
    }

    @Test
    void createShouldInsertTag() {
        when(templateTagMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        TemplateTagResponse response = templateTagService.create(request("现代简约"));

        assertThat(response.getTagName()).isEqualTo("现代简约");
        assertThat(response.getEnabled()).isTrue();
        verify(templateTagMapper).insert(any(TemplateTag.class));
        verify(auditLogService).logCreate(eq("template_tag"), anyString(), any(TemplateTag.class), any());
    }

    @Test
    void createShouldRejectDuplicateName() {
        when(templateTagMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> templateTagService.create(request("现代简约")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("同名标签已存在");
        verify(templateTagMapper, never()).insert(any(TemplateTag.class));
    }

    @Test
    void updateShouldRenameAndSyncSchemeTags() {
        when(templateTagMapper.selectById("TAG-1")).thenReturn(tag("TAG-1", "旧标签", true));
        when(templateTagMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setIsTemplate(true);
        scheme.setTemplateTags("[\"旧标签\",\"客厅\"]");
        when(schemeMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(scheme));

        TemplateTagResponse response = templateTagService.update("TAG-1", request("新标签"));

        assertThat(response.getTagName()).isEqualTo("新标签");
        verify(templateTagMapper).updateById(any(TemplateTag.class));
        // 存量模板方案中的旧标签名被替换
        verify(schemeMapper).updateById(any(Scheme.class));
        assertThat(scheme.getTemplateTags()).isEqualTo("[\"新标签\",\"客厅\"]");
        verify(auditLogService).logUpdate(eq("template_tag"), eq("TAG-1"), any(), any(TemplateTag.class), any());
    }

    @Test
    void updateShouldRejectDuplicateName() {
        when(templateTagMapper.selectById("TAG-1")).thenReturn(tag("TAG-1", "标签A", true));
        when(templateTagMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> templateTagService.update("TAG-1", request("标签B")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("同名标签已存在");
        verify(templateTagMapper, never()).updateById(any(TemplateTag.class));
    }

    @Test
    void deleteShouldRejectWhenUsedByTemplate() {
        when(templateTagMapper.selectById("TAG-1")).thenReturn(tag("TAG-1", "现代简约", true));
        when(schemeMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L);

        assertThatThrownBy(() -> templateTagService.delete("TAG-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无法删除");
        verify(templateTagMapper, never()).deleteById(anyString());
    }

    @Test
    void deleteShouldSucceedWhenUnused() {
        when(templateTagMapper.selectById("TAG-1")).thenReturn(tag("TAG-1", "现代简约", true));
        when(schemeMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        templateTagService.delete("TAG-1");

        verify(templateTagMapper).deleteById("TAG-1");
        verify(auditLogService).logDelete(eq("template_tag"), eq("TAG-1"), any(), any());
    }

    @Test
    void deleteShouldRejectMissingTag() {
        when(templateTagMapper.selectById("TAG-X")).thenReturn(null);

        assertThatThrownBy(() -> templateTagService.delete("TAG-X"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void simpleListShouldReturnEnabledOnly() {
        when(templateTagMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(tag("TAG-1", "现代简约", true)));

        List<TemplateTagResponse> tags = templateTagService.simpleList();

        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).getTagName()).isEqualTo("现代简约");
    }

    @Test
    void validateTagNamesShouldPassWhenAllExist() {
        when(templateTagMapper.selectList(null))
            .thenReturn(List.of(tag("TAG-1", "现代简约", true), tag("TAG-2", "客厅", true)));

        templateTagService.validateTagNames(List.of("现代简约", "客厅"));
        // 不抛异常即通过
    }

    @Test
    void validateTagNamesShouldRejectInvalidNames() {
        when(templateTagMapper.selectList(null))
            .thenReturn(List.of(tag("TAG-1", "现代简约", true)));

        assertThatThrownBy(() -> templateTagService.validateTagNames(List.of("现代简约", "不存在的标签")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在的标签");
    }
}
