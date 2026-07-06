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
}
