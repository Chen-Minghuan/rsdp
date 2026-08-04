package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

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
    private String status;

    /**
     * 同义词别名列表（字典匹配时标准名未命中则按别名匹配）。
     */
    private List<String> aliases;

    /**
     * 备注说明；六维字典存视觉判别要点（一句话锚点），供 AI prompt 注入与识别后归一使用（V24）。
     */
    private String remark;
}
