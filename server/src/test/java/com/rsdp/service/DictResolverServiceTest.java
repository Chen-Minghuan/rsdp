package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.CategoryDict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 字典解析服务单元测试：精确匹配优先、别名兜底匹配。
 */
class DictResolverServiceTest {

    private DictService dictService;
    private DictResolverService dictResolverService;

    @BeforeEach
    void setUp() {
        dictService = mock(DictService.class);
        dictResolverService = new DictResolverService(dictService, new ObjectMapper());

        CategoryDict leather = dict("material", "LE", "皮革", null, "[\"真皮\",\"头层牛皮\",\"黄牛皮\"]");
        CategoryDict wood = dict("material", "WO", "实木", null, "[\"橡木\",\"胡桃木\"]");
        CategoryDict linen = dict("material", "LI", "亚麻/棉麻", null, null);
        when(dictService.listByType("material")).thenReturn(List.of(leather, wood, linen));
    }

    private CategoryDict dict(String type, String code, String name, String nameEn, String aliases) {
        CategoryDict d = new CategoryDict();
        d.setDictType(type);
        d.setDictCode(code);
        d.setDictName(name);
        d.setDictNameEn(nameEn);
        d.setAliases(aliases);
        return d;
    }

    @Test
    void resolveCodeByName_shouldMatchExactName() {
        assertThat(dictResolverService.resolveCodeByName("material", "皮革")).isEqualTo("LE");
        assertThat(dictResolverService.resolveCodeByName("material", "实木")).isEqualTo("WO");
    }

    @Test
    void resolveCodeByName_shouldMatchAliasWhenExactMisses() {
        assertThat(dictResolverService.resolveCodeByName("material", "头层牛皮")).isEqualTo("LE");
        assertThat(dictResolverService.resolveCodeByName("material", "橡木")).isEqualTo("WO");
    }

    @Test
    void resolveCodeByName_shouldPreferExactOverAlias() {
        // "真皮"既是 LE 的别名，此处构造另一项以"真皮"为标准名，精确匹配应胜出
        CategoryDict zp = dict("fabric", "ZP", "真皮", null, "[\"牛皮\"]");
        when(dictService.listByType("fabric")).thenReturn(List.of(zp));
        assertThat(dictResolverService.resolveCodeByName("fabric", "真皮")).isEqualTo("ZP");
        assertThat(dictResolverService.resolveCodeByName("fabric", "牛皮")).isEqualTo("ZP");
    }

    @Test
    void resolveCodeByName_shouldReturnNullWhenNoMatch() {
        assertThat(dictResolverService.resolveCodeByName("material", "不存在的材质")).isNull();
        assertThat(dictResolverService.resolveCodeByName("material", null)).isNull();
        assertThat(dictResolverService.resolveCodeByName("material", "  ")).isNull();
    }

    @Test
    void resolveCodesByNames_shouldResolveMixedExactAndAlias() {
        List<String> codes = dictResolverService.resolveCodesByNames("material",
            List.of("皮革", "头层牛皮", "橡木", "未知材质"));
        assertThat(codes).containsExactly("LE", "WO");
    }

    @Test
    void resolveCodesByNames_shouldReturnEmptyForNullOrEmpty() {
        assertThat(dictResolverService.resolveCodesByNames("material", null)).isEmpty();
        assertThat(dictResolverService.resolveCodesByNames("material", List.of())).isEmpty();
    }

    @Test
    void resolveCodeByName_shouldTolerateBrokenAliasJson() {
        CategoryDict broken = dict("material", "GL", "玻璃", null, "not-a-json");
        when(dictService.listByType("material")).thenReturn(List.of(broken));
        assertThat(dictResolverService.resolveCodeByName("material", "玻璃")).isEqualTo("GL");
    }
}
