package com.rsdp.dto.response;

import lombok.Data;

/**
 * 引用指定产品的方案信息（产品彻底删除拦截提示用）。
 *
 * <p>由 ProductPurgeMapper.listSchemeRefsByRspu 返回，含软删方案。</p>
 */
@Data
public class SchemeRefInfo {

    /** 方案 ID */
    private String schemeId;

    /** 方案名称 */
    private String schemeName;

    /** 是否使用中（scheme.deleted_at 为空）；false = 已软删除（在方案回收站中） */
    private Boolean inUse;
}
