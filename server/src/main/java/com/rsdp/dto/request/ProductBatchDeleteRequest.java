package com.rsdp.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 产品批量删除请求。
 */
@Data
public class ProductBatchDeleteRequest {

    /** 待删除的 RSPU ID 列表（单次最多 100 个） */
    @NotEmpty(message = "待删除产品列表不能为空")
    @Size(max = 100, message = "单次批量删除不能超过 100 个产品")
    private List<String> rspuIds;
}
