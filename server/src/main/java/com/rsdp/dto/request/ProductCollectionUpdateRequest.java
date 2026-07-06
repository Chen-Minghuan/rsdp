package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新产品集请求。
 */
@Data
public class ProductCollectionUpdateRequest {

    /** 产品集编码。 */
    @Size(max = 32, message = "产品集编码不能超过 32 个字符")
    private String collectionCode;

    /** 产品集名称。 */
    @Size(max = 100, message = "产品集名称不能超过 100 个字符")
    private String name;

    /** 描述。 */
    @Size(max = 1000, message = "描述不能超过 1000 个字符")
    private String description;

    /** 覆盖品类编码列表。 */
    private List<String> categoryCodes;

    /** 覆盖风格编码列表。 */
    private List<String> styleCodes;

    /** 目标客群标签。 */
    private List<String> targetSegments;

    /** 是否首页推荐。 */
    private Boolean isFeatured;

    /** 排序号。 */
    private Integer sortOrder;

    /** 状态。 */
    @Size(max = 20, message = "状态不能超过 20 个字符")
    private String status;

    /** 包含的 RSPU ID 列表（按顺序，会覆盖原有项）。 */
    private List<String> rspuIds;
}
