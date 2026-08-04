package com.rsdp.service;

import com.rsdp.entity.DictAlias;
import com.rsdp.mapper.DictAliasMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DictAliasService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DictAliasServiceTest {

    @InjectMocks
    private DictAliasService dictAliasService;

    @Mock
    private DictAliasMapper dictAliasMapper;

    @Test
    void resolveAlias_shouldReturnCodeWhenFound() {
        DictAlias alias = new DictAlias();
        alias.setDictType("category");
        alias.setAliasName("茶桌");
        alias.setDictCode("TB");
        when(dictAliasMapper.selectOne(any())).thenReturn(alias);

        assertEquals("TB", dictAliasService.resolveAlias("category", "茶桌"));
    }

    @Test
    void resolveAlias_shouldReturnNullWhenMissingOrBlank() {
        when(dictAliasMapper.selectOne(any())).thenReturn(null);

        assertNull(dictAliasService.resolveAlias("category", "未知词"));
        assertNull(dictAliasService.resolveAlias("category", ""));
        assertNull(dictAliasService.resolveAlias(null, "茶桌"));
    }

    @Test
    void resolveAliases_shouldReturnBatchMap() {
        DictAlias a1 = new DictAlias();
        a1.setDictType("category");
        a1.setAliasName("茶桌");
        a1.setDictCode("TB");
        DictAlias a2 = new DictAlias();
        a2.setDictType("category");
        a2.setAliasName("主椅");
        a2.setDictCode("FS");
        when(dictAliasMapper.selectList(any())).thenReturn(List.of(a1, a2));

        Map<String, String> result = dictAliasService.resolveAliases("category", List.of("茶桌", "主椅", "未知词"));

        assertEquals(2, result.size());
        assertEquals("TB", result.get("茶桌"));
        assertEquals("FS", result.get("主椅"));
        assertTrue(dictAliasService.resolveAliases("category", List.of()).isEmpty());
    }

    @Test
    void saveAlias_shouldInsertWhenAbsent() {
        when(dictAliasMapper.selectOne(any())).thenReturn(null);

        dictAliasService.saveAlias("category", "茶桌", "TB", "user-1");

        ArgumentCaptor<DictAlias> captor = ArgumentCaptor.forClass(DictAlias.class);
        verify(dictAliasMapper, times(1)).insert(captor.capture());
        DictAlias saved = captor.getValue();
        assertEquals("category", saved.getDictType());
        assertEquals("茶桌", saved.getAliasName());
        assertEquals("TB", saved.getDictCode());
        assertEquals("ai_confirmed", saved.getSource());
        assertEquals("user-1", saved.getCreatedBy());
    }

    @Test
    void saveAlias_shouldUpdateWhenCodeChanged() {
        DictAlias existing = new DictAlias();
        existing.setId(1L);
        existing.setDictType("category");
        existing.setAliasName("茶桌");
        existing.setDictCode("FS");
        when(dictAliasMapper.selectOne(any())).thenReturn(existing);

        dictAliasService.saveAlias("category", "茶桌", "TB", "user-1");

        verify(dictAliasMapper, times(1)).updateById(existing);
        assertEquals("TB", existing.getDictCode());
        verify(dictAliasMapper, never()).insert(any(DictAlias.class));
    }

    @Test
    void saveAlias_shouldSkipWhenSameCode() {
        DictAlias existing = new DictAlias();
        existing.setId(1L);
        existing.setDictType("category");
        existing.setAliasName("茶桌");
        existing.setDictCode("TB");
        when(dictAliasMapper.selectOne(any())).thenReturn(existing);

        dictAliasService.saveAlias("category", "茶桌", "TB", "user-1");

        verify(dictAliasMapper, never()).updateById(any(DictAlias.class));
        verify(dictAliasMapper, never()).insert(any(DictAlias.class));
    }

    @Test
    void saveAlias_shouldSwallowConcurrentUniqueViolation() {
        // 并发下另一请求已写入同 (dict_type, alias_name)：唯一约束冲突安全跳过
        when(dictAliasMapper.selectOne(any())).thenReturn(null);
        when(dictAliasMapper.insert(any(DictAlias.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key uk_dict_alias"));

        assertDoesNotThrow(() -> dictAliasService.saveAlias("category", "茶桌", "TB", "user-1"));
    }
}
