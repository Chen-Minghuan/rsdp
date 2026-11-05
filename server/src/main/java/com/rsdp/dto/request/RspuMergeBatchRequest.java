package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量同款合并请求（归类工作台）：逐项执行既有单对合并语义，
 * 单项失败不影响其余项（每项独立事务）。
 */
@Data
public class RspuMergeBatchRequest {

    /** 合并项列表（每项语义同单对合并）。 */
    @NotEmpty(message = "合并项不能为空")
    @Valid
    private List<RspuMergeRequest> items;
}
