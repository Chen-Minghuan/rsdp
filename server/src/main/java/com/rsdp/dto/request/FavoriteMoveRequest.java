package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收藏条目移动文件夹请求。folderId 为空表示移出文件夹（未归档）。
 */
@Data
public class FavoriteMoveRequest {

    @Size(max = 64, message = "文件夹 ID 不能超过 64 个字符")
    private String folderId;
}
