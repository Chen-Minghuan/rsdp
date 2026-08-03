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

    // ==================== 六维标签归一（resolveSixDimCode） ====================

    private CategoryDict sixDimDict(String dim, String code, String name, String parentCode, String aliases) {
        CategoryDict d = dict("six_dim_" + dim, code, name, null, aliases);
        d.setParentCode(parentCode);
        return d;
    }

    private void stubSixDimC() {
        when(dictService.listByType("six_dim_C")).thenReturn(List.of(
            sixDimDict("C", "SF-宽厚扶手", "宽厚扶手", "SF", "[\"宽扶手\",\"厚扶手\",\"面包扶手\"]"),
            sixDimDict("C", "SF-异形/其他", "异形/其他", "SF", null),
            sixDimDict("C", "TB-直边", "直边", "TB", null),
            sixDimDict("C", "DT-直边", "直边", "DT", null)
        ));
    }

    @Test
    void resolveSixDimCode_shouldMatchExactNameToPrefixedCode() {
        stubSixDimC();
        assertThat(dictResolverService.resolveSixDimCode("C", "SF", "宽厚扶手")).isEqualTo("SF-宽厚扶手");
    }

    @Test
    void resolveSixDimCode_shouldMatchAlias() {
        stubSixDimC();
        assertThat(dictResolverService.resolveSixDimCode("C", "SF", "面包扶手")).isEqualTo("SF-宽厚扶手");
    }

    @Test
    void resolveSixDimCode_shouldPassThroughPrefixedCode() {
        stubSixDimC();
        assertThat(dictResolverService.resolveSixDimCode("C", "SF", "SF-宽厚扶手")).isEqualTo("SF-宽厚扶手");
    }

    @Test
    void resolveSixDimCode_shouldIsolateByParentCode() {
        stubSixDimC();
        // TB/DT 同名"直边"各自归到自己品类的码，互不串扰
        assertThat(dictResolverService.resolveSixDimCode("C", "TB", "直边")).isEqualTo("TB-直边");
        assertThat(dictResolverService.resolveSixDimCode("C", "DT", "直边")).isEqualTo("DT-直边");
        // SF 品类下没有"直边"条目，不应命中其他品类
        assertThat(dictResolverService.resolveSixDimCode("C", "SF", "直边")).isNull();
    }

    @Test
    void resolveSixDimCode_shouldFallbackOtherToIrregularEntry() {
        stubSixDimC();
        // AI 输出"其他"，字典兜底条目名为"异形/其他"，宽松匹配命中
        assertThat(dictResolverService.resolveSixDimCode("C", "SF", "其他")).isEqualTo("SF-异形/其他");
    }

    @Test
    void resolveSixDimCode_shouldReturnNullWhenMissOrNoCategory() {
        stubSixDimC();
        assertThat(dictResolverService.resolveSixDimCode("C", "SF", "某种没收录的扶手")).isNull();
        // 品类为空不做归一（避免跨品类同名歧义）
        assertThat(dictResolverService.resolveSixDimCode("C", null, "宽厚扶手")).isNull();
        assertThat(dictResolverService.resolveSixDimCode("C", "", "宽厚扶手")).isNull();
        assertThat(dictResolverService.resolveSixDimCode("C", "SF", null)).isNull();
        // 品类在字典中无条目
        assertThat(dictResolverService.resolveSixDimCode("C", "BD", "宽厚扶手")).isNull();
    }
}
