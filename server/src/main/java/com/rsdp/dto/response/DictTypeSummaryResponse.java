package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 字典类型汇总响应（字典管理中心左栏数据源）。
 */
@Data
@AllArgsConstructor
public class DictTypeSummaryResponse {

    private String dictType;
    private Long count;
}
