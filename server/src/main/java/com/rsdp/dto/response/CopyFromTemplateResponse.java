package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 套用模板创建方案响应。
 */
@Data
public class CopyFromTemplateResponse {

    /** 新创建的方案详情 */
    private SchemeResponse scheme;

    /** 模板价格与当前最新价的差异项 */
    private List<PriceChangeResponse> priceChanges;

    /** 模板中已失效（RSKU 不存在或已删除）被跳过的 rskuId 列表 */
    private List<String> skippedRskuIds;
}
