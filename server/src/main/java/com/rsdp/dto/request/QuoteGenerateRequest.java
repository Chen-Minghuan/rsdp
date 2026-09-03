package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 生成报价单请求。
 */
@Data
public class QuoteGenerateRequest {

    @NotEmpty(message = "请选择至少一个 RSKU")
    @Valid
    private List<QuoteItemRequest> items;

    /** 报价口径：cost=成本核价（内部，默认）| sale=销售报价（对客户）；非法值抛业务异常 */
    private String mode;
}
