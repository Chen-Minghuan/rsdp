package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.CategoryDictMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
        dict.setDictType("category");
        dict.setDictCode("NEW");
        dict.setDictName("新品类");

        assertThatThrownBy(() -> dictService.createDict(dict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不允许扩展该字典类型");

        verify(categoryDictMapper, never()).selectOne(any());
        verify(categoryDictMapper, never()).insert(any(CategoryDict.class));
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
}
