package com.rsdp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SixDimSchemaConfigTest {

    @Test
    void getSchema_shouldReturnCategorySpecificSchema() {
        var schema = SixDimSchemaConfig.getSchema("FC");
        assertThat(schema.categoryName()).isEqualTo("柜类");
        assertThat(schema.dims().get("B").label()).isEqualTo("门板/抽屉特征");
        assertThat(schema.dims().get("C").label()).isEqualTo("拉手/五金特征");
    }

    @Test
    void getSchema_shouldReturnGenericSchemaForUnknownCategory() {
        var schema = SixDimSchemaConfig.getSchema("UNKNOWN");
        assertThat(schema.categoryName()).isEqualTo("通用");
        assertThat(schema.dims().get("A").label()).isEqualTo("整体造型/轮廓");
    }

    @Test
    void getSchema_shouldReturnGenericSchemaForNullCategory() {
        var schema = SixDimSchemaConfig.getSchema(null);
        assertThat(schema.categoryName()).isEqualTo("通用");
    }

    @Test
    void buildPromptDescription_shouldContainDimLabels() {
        String description = SixDimSchemaConfig.buildPromptDescription("TB");
        assertThat(description).contains("A = 整体造型/轮廓");
        assertThat(description).contains("B = 台面形态");
        assertThat(description).contains("D = 桌腿/底座");
    }

    @Test
    void getSchema_shouldReturnDiningTableSchema() {
        var schema = SixDimSchemaConfig.getSchema("DT");
        assertThat(schema.categoryName()).isEqualTo("餐桌");
        assertThat(schema.dims().get("B").label()).isEqualTo("台面形态");
        assertThat(schema.dims().get("F").label()).isEqualTo("功能/展开方式");
    }

    @Test
    void getSchema_shouldReturnBedSchema() {
        var schema = SixDimSchemaConfig.getSchema("BD");
        assertThat(schema.categoryName()).isEqualTo("床");
        assertThat(schema.dims().get("B").label()).isEqualTo("床头");
        assertThat(schema.dims().get("F").label()).isEqualTo("储物/功能");
    }

    @Test
    void getSchema_shouldReturnLampSchema() {
        var schema = SixDimSchemaConfig.getSchema("LT");
        assertThat(schema.categoryName()).isEqualTo("灯具");
        assertThat(schema.dims().get("B").label()).isEqualTo("灯罩/出光");
        assertThat(schema.dims().get("D").label()).isEqualTo("安装/底座");
    }

    @Test
    void buildPromptDescription_shouldContainNewCategoryDimLabels() {
        String description = SixDimSchemaConfig.buildPromptDescription("BD");
        assertThat(description).contains("A = 整体造型");
        assertThat(description).contains("B = 床头");
        assertThat(description).contains("F = 储物/功能");
    }
}
