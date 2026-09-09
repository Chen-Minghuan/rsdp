package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.VectorTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图片向量表实体：一张图片一条当前向量（编码配置见
 * {@code com.rsdp.service.vector.ProductVectorProfile}）。
 *
 * <p>商品归属经 image_assets 关联，不冗余可变业务事实；
 * 图片物理删除时级联删除本行。</p>
 */
@Data
@TableName("product_image_embedding")
public class ProductImageEmbedding {

    /** 图片 ID（主键，FK → image_assets） */
    @TableId
    private String imageId;

    /** 编码配置标识（模型+维度+预处理+距离），不可随意改变含义 */
    private String profileId;

    /** 生成时图片内容版本（image_assets.content_revision） */
    private Long sourceRevision;

    /** 实际送入 embedding API 的字节 SHA-256（缩放后） */
    private String inputHash;

    /** 图片向量（pgvector vector(1024)） */
    @TableField(typeHandler = VectorTypeHandler.class)
    private float[] embedding;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
