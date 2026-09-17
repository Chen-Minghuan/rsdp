package com.rsdp.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * LLM 推荐理由输出（内部 DTO，RecommendNode 解析模型输出用）。
 *
 * <p>Java 侧会严格校验：rspuId 必须属于候选集合，evidenceRefs 必须是
 * snapshot 字段名子集，非法条目剔除。</p>
 */
@Data
public class LlmRecommendation {

    private List<Item> items;

    /** 单条推荐理由。 */
    @Data
    public static class Item {

        private String rspuId;

        private List<Highlight> highlights;
    }

    /** 单条亮点。 */
    @Data
    public static class Highlight {

        private String text;

        private List<String> evidenceRefs;
    }
}
