package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 订单收件信息/备注更新请求（仅 PENDING 状态可改）。
 */
@Data
public class OrderUpdateRequest {

    @Size(max = 64, message = "收件人不能超过 64 个字符")
    private String receiverName;

    @Size(max = 32, message = "联系电话不能超过 32 个字符")
    private String receiverPhone;

    @Size(max = 128, message = "收件地区不能超过 128 个字符")
    private String receiverArea;

    @Size(max = 256, message = "详细地址不能超过 256 个字符")
    private String receiverAddress;

    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String remark;
}
