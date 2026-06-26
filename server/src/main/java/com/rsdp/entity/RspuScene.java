package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU 多场景关联实体。
 */
@Data
@TableName("rspu_scene")
public class RspuScene {

    private String rspuId;
    private String dictType;
    private String sceneCode;
    private LocalDateTime createdAt;
}
