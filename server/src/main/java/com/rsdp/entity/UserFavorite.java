package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户收藏实体。
 */
@Data
@TableName("user_favorite")
public class UserFavorite {

    @TableId
    private String favoriteId;
    private String userId;
    private String rspuId;
    private String groupName;
    private LocalDateTime createdAt;
}
