package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏夹文件夹实体（两级模型：文件夹 + 收藏条目）。
 */
@Data
@TableName("favorite_folder")
public class FavoriteFolder {

    @TableId
    private String folderId;

    private String userId;

    private String folderName;

    private Integer sortOrder;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
