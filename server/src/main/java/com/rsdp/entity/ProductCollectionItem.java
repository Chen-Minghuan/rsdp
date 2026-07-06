package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 产品集项实体。
 */
@Data
@TableName("product_collection_item")
public class ProductCollectionItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String collectionId;
    private String rspuId;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
