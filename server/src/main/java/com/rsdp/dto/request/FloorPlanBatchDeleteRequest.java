package com.rsdp.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 户型图分析记录批量删除请求。
 */
@Data
public class FloorPlanBatchDeleteRequest {

    /** 待删除的分析批次 ID 列表（单次最多 100 个）。 */
    @NotEmpty(message = "待删除户型图分析记录不能为空")
    @Size(max = 100, message = "单次批量删除不能超过 100 条户型图分析记录")
    private List<String> analysisIds;
}
