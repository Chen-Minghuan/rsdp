package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("image_assets")
public class ImageAssets {
    @TableId
    private String imageId;
    private String rspuId;
    private String variantId;
    private String rskuId;
    private String imageType;
    private String storagePath;
    private String storageUrl;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private String format;

    @TableField(value = "is_primary")
    private Boolean primary;

    private Boolean aiProcessed;
    private BigDecimal qualityScore;
    private String uploadedBy;
    private LocalDateTime createdAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
