package com.rsdp.service.chroma;

import com.rsdp.entity.RspuMaster;

import java.util.HashMap;
import java.util.Map;

/**
 * ChromaDB 向量元数据构建器，统一产品图片向量的 metadata 字段，
 * 避免多处重复构建导致字段不一致。
 */
public final class ChromaMetadataBuilder {

    private ChromaMetadataBuilder() {
    }

    /**
     * 构建产品图片向量的元数据。
     *
     * @param rspu      RSPU 主档
     * @param imageSize 图片字节数
     * @return metadata map
     */
    public static Map<String, Object> buildProductMetadata(RspuMaster rspu, long imageSize) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("rspu_id", rspu.getRspuId());
        metadata.put("category_code", rspu.getCategoryCode());
        metadata.put("positioning_label", rspu.getPositioningLabel());
        metadata.put("color_primary_name", rspu.getColorPrimaryName());
        metadata.put("material_tags", rspu.getMaterialTags());
        metadata.put("scene_tags", rspu.getSceneTags());
        metadata.put("status", rspu.getStatus());
        metadata.put("image_size", imageSize);
        return metadata;
    }
}
