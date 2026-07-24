package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网首页轮播 Banner 实体。
 */
@Data
@TableName("platform_banner")
public class PlatformBanner {

    @TableId
    private String bannerId;

    /** 展示位置（如 home_top 首页顶部轮播） */
    private String position;

    private String title;

    /** Banner 图（image_assets.image_id） */
    private String imageId;

    /** 跳转类型：none=不跳转 / rspu=产品详情 / url=外链 */
    private String linkType;

    /** 跳转目标（rspuId 或外链 URL） */
    private String linkValue;

    private Integer sortOrder;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
