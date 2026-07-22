package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收藏请求。
 */
@Data
public class FavoriteRequest {

    @NotBlank(message = "产品 ID 不能为空")
    private String rspuId;

    @Size(max = 64, message = "分组名称不能超过 64 个字符")
    private String groupName;

    /** 目标文件夹 ID（优先于 groupName；为空且 groupName 也为空时收藏为未归档） */
    @Size(max = 64, message = "文件夹 ID 不能超过 64 个字符")
    private String folderId;
}
