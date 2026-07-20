package com.rsdp.service.chroma;

import com.rsdp.entity.RspuMaster;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChromaMetadataBuilder} 单元测试。
 */
class ChromaMetadataBuilderTest {

    @Test
    void buildProductMetadata_shouldContainAllFields() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setColorPrimaryName("焦糖棕");
        rspu.setMaterialTags("[\"WO\",\"LI\"]");
        rspu.setSceneTags("[\"LIVING\"]");
        rspu.setStatus("active");

        Map<String, Object> metadata = ChromaMetadataBuilder.buildProductMetadata(rspu, 12345L);

        assertThat(metadata).containsEntry("rspu_id", "RSPU-001");
        assertThat(metadata).containsEntry("category_code", "FS");
        assertThat(metadata).containsEntry("positioning_label", "MC");
        assertThat(metadata).containsEntry("color_primary_name", "焦糖棕");
        assertThat(metadata).containsEntry("material_tags", "[\"WO\",\"LI\"]");
        assertThat(metadata).containsEntry("scene_tags", "[\"LIVING\"]");
        assertThat(metadata).containsEntry("status", "active");
        assertThat(metadata).containsEntry("image_size", 12345L);
        assertThat(metadata).hasSize(8);
    }

    @Test
    void buildProductMetadata_shouldKeepNullFieldsAsNull() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-002");

        Map<String, Object> metadata = ChromaMetadataBuilder.buildProductMetadata(rspu, 0L);

        assertThat(metadata).containsEntry("rspu_id", "RSPU-002");
        assertThat(metadata).containsKey("category_code");
        assertThat(metadata.get("category_code")).isNull();
        assertThat(metadata).containsEntry("image_size", 0L);
    }
}
