package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.CategoryDictMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DictService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DictServiceTest {

    @Mock
    private CategoryDictMapper categoryDictMapper;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DictService dictService;

    @Test
    void listByType_shouldReturnDicts() {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("PE");
        dict.setDictName("真皮");

        when(categoryDictMapper.selectByType("material")).thenReturn(List.of(dict));

        List<CategoryDict> result = dictService.listByType("material");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDictCode()).isEqualTo("PE");
    }

    @Test
    void createDict_material_shouldSucceed() {
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(categoryDictMapper.selectOne(argThat(qw -> qw != null && qw.getCustomSqlSegment().contains("ORDER BY"))))
            .thenReturn(null);

        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode(" velvet ");
        dict.setDictName(" 天鹅绒 ");
        dict.setDictNameEn("Velvet");

        dictService.createDict(dict);

        assertThat(dict.getDictType()).isEqualTo("material");
        assertThat(dict.getDictCode()).isEqualTo("VELVET");
        assertThat(dict.getDictName()).isEqualTo("天鹅绒");
        assertThat(dict.getStatus()).isEqualTo("active");
        assertThat(dict.getSortOrder()).isEqualTo(1);

        ArgumentCaptor<CategoryDict> captor = ArgumentCaptor.forClass(CategoryDict.class);
        verify(categoryDictMapper).insert(captor.capture());
        assertThat(captor.getValue().getDictCode()).isEqualTo("VELVET");
    }

    @Test
    void createDict_scene_shouldSucceed() {
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(categoryDictMapper.selectOne(argThat(qw -> qw != null && qw.getCustomSqlSegment().contains("ORDER BY"))))
            .thenReturn(null);

        CategoryDict dict = new CategoryDict();
        dict.setDictType("SCENE");
        dict.setDictCode("balcony");
        dict.setDictName("阳台");

        dictService.createDict(dict);

        assertThat(dict.getDictType()).isEqualTo("scene");
        assertThat(dict.getDictCode()).isEqualTo("BALCONY");
    }

    @Test
    void createDict_duplicate_shouldThrow() {
        CategoryDict existing = new CategoryDict();
        existing.setDictType("material");
        existing.setDictCode("PE");
        existing.setDictName("真皮");

        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("PE");
        dict.setDictName("皮革");

        assertThatThrownBy(() -> dictService.createDict(dict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("字典项已存在");

        verify(categoryDictMapper, never()).insert(any(CategoryDict.class));
    }

    @Test
    void createDict_forbiddenType_shouldThrow() {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("design_order_status");
        dict.setDictCode("NEW");
        dict.setDictName("新状态");

        assertThatThrownBy(() -> dictService.createDict(dict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不允许扩展该字典类型");

        verify(categoryDictMapper, never()).selectOne(any());
        verify(categoryDictMapper, never()).insert(any(CategoryDict.class));
    }

    @Test
    void createDict_fabric_shouldSucceed() {
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        CategoryDict dict = new CategoryDict();
        dict.setDictType("FABRIC");
        dict.setDictCode("chenille");
        dict.setDictName("雪尼尔提花");

        dictService.createDict(dict);

        assertThat(dict.getDictType()).isEqualTo("fabric");
        assertThat(dict.getDictCode()).isEqualTo("CHENILLE");
        verify(categoryDictMapper).insert(any(CategoryDict.class));
        verify(auditLogService).logCreate(eq("category_dict"), eq("fabric:CHENILLE"), any(), any());
    }

    @Test
    void createDict_invalidCode_shouldThrow() {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("纯皮-1");
        dict.setDictName("真皮");

        assertThatThrownBy(() -> dictService.createDict(dict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("字典编码只能包含大写字母和数字");

        verify(categoryDictMapper, never()).insert(any(CategoryDict.class));
    }

    @Test
    void createDict_blankName_shouldThrow() {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("PE");
        dict.setDictName("  ");

        assertThatThrownBy(() -> dictService.createDict(dict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("字典名称不能为空");

        verify(categoryDictMapper, never()).insert(any(CategoryDict.class));
    }

    @Test
    void createDict_shouldUseNextSortOrder() {
        CategoryDict last = new CategoryDict();
        last.setSortOrder(5);

        when(categoryDictMapper.selectOne(argThat(qw -> qw != null && qw.getCustomSqlSegment().contains("ORDER BY"))))
            .thenReturn(last);
        when(categoryDictMapper.selectOne(argThat(qw -> qw != null && !qw.getCustomSqlSegment().contains("ORDER BY"))))
            .thenReturn(null);

        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("LINEN");
        dict.setDictName("亚麻");

        dictService.createDict(dict);

        assertThat(dict.getSortOrder()).isEqualTo(6);
    }

    @Test
    void updateDict_shouldUpdateNameAndAliases() {
        CategoryDict existing = new CategoryDict();
        existing.setDictType("material");
        existing.setDictCode("LE");
        existing.setDictName("皮革");
        existing.setStatus("active");
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        CategoryDict result = dictService.updateDict("material", "LE", "头层皮革", "Leather",
            List.of("真皮", "牛皮"), 10);

        assertThat(result.getDictName()).isEqualTo("头层皮革");
        assertThat(result.getDictNameEn()).isEqualTo("Leather");
        assertThat(result.getAliases()).isEqualTo("[\"真皮\",\"牛皮\"]");
        assertThat(result.getSortOrder()).isEqualTo(10);
        verify(categoryDictMapper).updateById(existing);
        verify(auditLogService).logUpdate(eq("category_dict"), eq("material:LE"), any(), any(), any());
    }

    @Test
    void updateDict_nullFields_shouldKeepOriginal() {
        CategoryDict existing = new CategoryDict();
        existing.setDictType("material");
        existing.setDictCode("LE");
        existing.setDictName("皮革");
        existing.setDictNameEn("Leather");
        existing.setStatus("active");
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        CategoryDict result = dictService.updateDict("material", "LE", null, null, null, null);

        assertThat(result.getDictName()).isEqualTo("皮革");
        assertThat(result.getDictNameEn()).isEqualTo("Leather");
        assertThat(result.getAliases()).isNull();
        verify(categoryDictMapper).updateById(existing);
    }

    @Test
    void updateDict_readonlyType_shouldThrow() {
        assertThatThrownBy(() -> dictService.updateDict("review_status", "DONE", "已复核", null, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不允许通过界面维护");

        verify(categoryDictMapper, never()).updateById(any(CategoryDict.class));
    }

    @Test
    void updateDict_notFound_shouldThrow() {
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> dictService.updateDict("material", "NOPE", "名称", null, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("字典项不存在");
    }

    @Test
    void updateDictStatus_shouldDisable() {
        CategoryDict existing = new CategoryDict();
        existing.setDictType("fabric");
        existing.setDictCode("WB");
        existing.setDictName("网布");
        existing.setStatus("active");
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        CategoryDict result = dictService.updateDictStatus("fabric", "WB", "disabled");

        assertThat(result.getStatus()).isEqualTo("disabled");
        verify(categoryDictMapper).updateById(existing);
        verify(auditLogService).logUpdate(eq("category_dict"), eq("fabric:WB"), any(), any(), any());
    }

    @Test
    void updateDictStatus_invalidStatus_shouldThrow() {
        assertThatThrownBy(() -> dictService.updateDictStatus("fabric", "WB", "deleted"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active 或 disabled");

        verify(categoryDictMapper, never()).updateById(any(CategoryDict.class));
    }

    @Test
    void listTypeSummary_shouldMapRows() {
        when(categoryDictMapper.countGroupByType()).thenReturn(List.of(
            java.util.Map.of("dictType", "fabric", "count", 12L),
            java.util.Map.of("dictType", "material", "count", 19L)));

        var result = dictService.listTypeSummary();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getDictType()).isEqualTo("fabric");
        assertThat(result.get(0).getCount()).isEqualTo(12L);
    }

    @Test
    void listAllByType_shouldIncludeDisabled() {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("PE");
        dict.setStatus("disabled");
        when(categoryDictMapper.selectAllByType("material")).thenReturn(List.of(dict));

        List<CategoryDict> result = dictService.listAllByType("material");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("disabled");
    }
}
