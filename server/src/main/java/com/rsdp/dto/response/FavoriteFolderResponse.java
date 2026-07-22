package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏夹文件夹响应。
 */
@Data
public class FavoriteFolderResponse {

    private String folderId;
    private String folderName;
    private Integer sortOrder;
    private Integer favoriteCount;
    private LocalDateTime createdAt;
}
