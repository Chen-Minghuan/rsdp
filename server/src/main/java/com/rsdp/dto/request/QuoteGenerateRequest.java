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
}
