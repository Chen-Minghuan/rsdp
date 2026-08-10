package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 用户端官网类目树节点（GET /api/v1/public/categories）。
 *
 * <p>category_dict 按 parent_code 组装两级树；当前种子为单层，roots 即全部类目。</p>
 */
@Data
public class PublicCategoryResponse {

    private String dictCode;

    private String dictName;

    private String dictNameEn;

    private Integer sortOrder;

    /** 子类目（无子类目时为空列表）。 */
    private List<PublicCategoryResponse> children;
}
