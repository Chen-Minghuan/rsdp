package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收藏夹文件夹创建/更新请求。
 */
@Data
public class FavoriteFolderRequest {

    @NotBlank(message = "文件夹名称不能为空")
    @Size(max = 64, message = "文件夹名称不能超过 64 个字符")
    private String folderName;
}
