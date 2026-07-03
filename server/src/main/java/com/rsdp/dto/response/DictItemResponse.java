package com.rsdp.dto.response;

import lombok.Data;

/**
 * 字典项响应。
 */
@Data
public class DictItemResponse {

    private String dictCode;
    private String dictName;
    private String dictNameEn;
    private String parentCode;
    private Integer sortOrder;
}
