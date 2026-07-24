package com.rsdp.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 方案明细拖拽排序请求：itemIds 为全部明细按新顺序排列的完整列表。
 */
@Data
public class SchemeItemReorderRequest {

    @NotEmpty(message = "排序列表不能为空")
    private List<Long> itemIds;
}
