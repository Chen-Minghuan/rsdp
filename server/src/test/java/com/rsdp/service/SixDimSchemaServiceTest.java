package com.rsdp.service;

import com.rsdp.dto.response.SixDimSchemaResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.SixDimSchema;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SixDimSchemaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SixDimSchemaService} 单元测试（V25 配置化后替代原 SixDimSchemaConfigTest）。
 */
@ExtendWith(MockitoExtension.class)
class SixDimSchemaServiceTest {

    @Mock
    private SixDimSchemaMapper sixDimSchemaMapper;

    @Mock
    private DictService dictService;

    private SixDimSchemaService service;

    @BeforeEach
    void setUp() {
        service = new SixDimSchemaService(sixDimSchemaMapper, dictService);
        lenient().when(dictService.listByType("category")).thenReturn(List.of(categoryDict("SF", "沙发")));
    }

    private static CategoryDict categoryDict(String code, String name) {
        CategoryDict d = new CategoryDict();
        d.setDictType("category");
        d.setDictCode(code);
        d.setDictName(name);
        return d;
    }

    private static SixDimSchema row(String categoryCode, String dimKey, String label, String description, int sortOrder) {
        SixDimSchema row = new SixDimSchema();
        row.setCategoryCode(categoryCode);
        row.setDimKey(dimKey);
        row.setLabel(label);
        row.setDescription(description);
        row.setSortOrder(sortOrder);
        return row;
    }

    private static List<SixDimSchema> sfRows() {
        return List.of(
            row("SF", "A", "轮廓形态", "整体造型，如L型、弧形、一字型、模块化组合", 1),
            row("SF", "B", "靠背/背部特征", "靠背高度、倾斜角度、包裹性", 2),
            row("SF", "C", "扶手特征", "扶手形态，如无扶手、低扶手、宽厚扶手", 3),
            row("SF", "D", "腿部/底座特征", "落地式、细腿、金属脚、悬浮底座", 4),
            row("SF", "E", "表面材质", "皮革、布艺、羊羔绒、天鹅绒等", 5),
            row("SF", "F", "软包填充形态", "坐垫/靠背填充饱满度、绗缝、拉扣", 6)
        );
    }

    private static List<SixDimSchema> genericRows() {
        return List.of(
            row("GENERIC", "A", "整体造型/轮廓", "产品整体外观形态", 1),
            row("GENERIC", "B", "上部/背部特征", "座椅靠背、柜类背板/门板、桌类台面", 2),
            row("GENERIC", "C", "侧部/连接部", "扶手、侧板、台面边缘、连接结构", 3),
            row("GENERIC", "D", "支撑/底座", "腿部、底座、支脚、底盘", 4),
            row("GENERIC", "E", "表面材质", "主要表面材质与纹理", 5),
            row("GENERIC", "F", "功能/填充件", "软包填充、抽屉、层板等功能件", 6)
        );
    }

    @Test
    void getSchema_shouldReturnCategorySchemaWithResolvedName() {
        when(sixDimSchemaMapper.selectList(any())).thenReturn(sfRows());

        SixDimSchemaResponse schema = service.getSchema("sf");

        assertThat(schema.categoryCode()).isEqualTo("SF");
        assertThat(schema.categoryName()).isEqualTo("沙发");
        assertThat(schema.dims()).hasSize(6);
        assertThat(schema.dims().get("A").label()).isEqualTo("轮廓形态");
        assertThat(schema.dims().get("E").description()).contains("皮革");
    }

    @Test
    void getSchema_shouldFallbackToGenericWhenCategoryMissing() {
        when(sixDimSchemaMapper.selectList(any()))
            .thenReturn(List.of())      // 第一次：未知品类
            .thenReturn(genericRows()); // 第二次：GENERIC 兜底

        SixDimSchemaResponse schema = service.getSchema("UNKNOWN");

        assertThat(schema.categoryCode()).isEqualTo("GENERIC");
        assertThat(schema.categoryName()).isEqualTo("通用");
        assertThat(schema.dims()).hasSize(6);
        assertThat(schema.dims().get("F").label()).isEqualTo("功能/填充件");
    }

    @Test
    void buildPromptDescription_shouldRenderAllDims() {
        when(sixDimSchemaMapper.selectList(any())).thenReturn(sfRows());

        String description = service.buildPromptDescription("SF");

        assertThat(description).contains("本产品的六维标签定义如下");
        assertThat(description).contains("A = 轮廓形态：整体造型，如L型、弧形、一字型、模块化组合");
        assertThat(description).contains("F = 软包填充形态：坐垫/靠背填充饱满度、绗缝、拉扣");
    }

    @Test
    void updateDim_shouldPersistAndReturnUpdatedSchema() {
        SixDimSchema existing = row("SF", "C", "扶手特征", "扶手形态", 3);
        when(sixDimSchemaMapper.selectOne(any())).thenReturn(existing);
        when(sixDimSchemaMapper.selectList(any())).thenReturn(sfRows());

        SixDimSchemaResponse schema = service.updateDim("SF", "C", "扶手形态特征", "新说明");

        assertThat(existing.getLabel()).isEqualTo("扶手形态特征");
        assertThat(existing.getDescription()).isEqualTo("新说明");
        verify(sixDimSchemaMapper).updateById(existing);
        assertThat(schema.categoryCode()).isEqualTo("SF");
    }

    @Test
    void updateDim_shouldThrowWhenDefinitionMissing() {
        when(sixDimSchemaMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateDim("XX", "A", "标签", "说明"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("XX/A");
    }
}
