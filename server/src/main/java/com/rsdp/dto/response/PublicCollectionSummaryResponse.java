package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 用户端官网公开产品集列表项（GET /api/v1/public/collections）。
 *
 * <p>仅含展示字段，不含创建人等内部信息。</p>
 */
@Data
public class PublicCollectionSummaryResponse {

    private String collectionId;

    private String name;

    private String description;

    /** 覆盖品类编码列表（筛选项）。 */
    private List<String> categoryCodes;

    /** 覆盖风格编码列表（筛选项）。 */
    private List<String> styleCodes;

    /** 目标客群标签。 */
    private List<String> targetSegments;

    /** 封面图访问地址（集合内首个在售产品的主图，无则 null）。 */
    private String coverImageUrl;

    /** 集合内在售产品数量。 */
    private Integer itemCount;
}
