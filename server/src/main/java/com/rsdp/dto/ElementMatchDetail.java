package com.rsdp.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 单个元素的匹配明细。
 */
@Data
@Builder
public class ElementMatchDetail {

    /**
     * 元素类型：material / color / scene / category / mood / style。
     */
    private String type;

    /**
     * 元素值，如"胡桃木"。
     */
    private String value;

    /**
     * 匹配角色：must_have / compatible / avoid。
     */
    private String role;

    /**
     * 是否命中。
     */
    private boolean matched;

    /**
     * 说明。
     */
    private String reason;
}
