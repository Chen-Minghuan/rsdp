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

    /** 待删除的 RSPU ID 列表（单次最多 500 个，与列表页最大单页条数一致） */
    @NotEmpty(message = "待删除产品列表不能为空")
    @Size(max = 500, message = "单次批量删除不能超过 500 个产品")
    private List<String> rspuIds;
}
