package com.rsdp.agent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 推荐卡片项响应（与前端 RecommendItem 契约逐字段对应）。
 *
 * <p>snapshot 的 key 固定为：productName, categoryPath, primaryImageUrl,
 * colorPrimaryName, material, sizeText, retailPrice（与前端 ProductSnapshot 一致）；
 * reason 为 null 时表示无理由降级卡片。</p>
 */
@Data
public class RecommendItemResponse {

    private String itemId;

    private String batchId;

    private String rspuId;

    private Integer rank;

    /** 产品事实快照（key 集合见类注释，值为快照字段值）。 */
    private Map<String, Object> snapshot;

    private Reason reason;

    /** 推荐理由（highlights + evidenceRefs）。 */
    @Data
    public static class Reason {

        private List<Highlight> highlights;
    }

    /** 单条推荐亮点。 */
    @Data
    public static class Highlight {

        private String text;

        /** 证据引用（snapshot 字段名子集）。 */
        private List<String> evidenceRefs;
    }
}
