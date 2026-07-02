package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 多场景关联实体。
 *
 * <p>数据库主键为复合主键 (rspu_id, scene_code)。MyBatis-Plus 仅支持单一主键，
 * 因此将 rspu_id 标注为 {@code @TableId}，业务查询仍通过 QueryWrapper 按复合条件操作。</p>
 */
@Data
@TableName("rspu_scene")
public class RspuScene {

    @TableId
    private String rspuId;

    private String dictType;

    @TableField("scene_code")
    private String sceneCode;

    private LocalDateTime createdAt;
}
