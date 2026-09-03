package com.rsdp.dto.request;

import lombok.Data;

/**
 * 方案报价请求。
 */
@Data
public class SchemeQuoteRequest {

    /** 报价口径：cost=成本核价（内部，默认）| sale=销售报价（对客户）；非法值抛业务异常 */
    private String mode;
}
