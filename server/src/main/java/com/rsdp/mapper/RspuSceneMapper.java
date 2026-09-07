package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuScene;
import org.apache.ibatis.annotations.Mapper;

/**
 * RSPU 多场景关联 Mapper。
 *
 * <p>rspu_scene 为 (rspu_id, scene_code) 复合主键表，继承 {@link CompositeKeyMapper}
 * 禁用按伪主键单列的 updateById/deleteById/selectById；整组重建（delete by rspu_id + insert）
 * 按 rspu_id 构造 QueryWrapper 即可，单行读写使用下方复合键方法。</p>
 */
@Mapper
public interface RspuSceneMapper extends CompositeKeyMapper<RspuScene> {

    /**
     * 按复合主键 (rspu_id, scene_code) 查询单条关联。
     *
     * @param rspuId    RSPU ID
     * @param sceneCode 场景字典码
     * @return 关联实体，不存在时为 null
     */
    default RspuScene selectByCompositeKey(String rspuId, String sceneCode) {
        return selectOne(new QueryWrapper<RspuScene>()
            .eq("rspu_id", rspuId)
            .eq("scene_code", sceneCode));
    }

    /**
     * 按复合主键 (rspu_id, scene_code) 更新（SET 取实体非空字段）。
     *
     * @param entity 关联实体，rspuId/sceneCode 定位行
     * @return 影响行数
     */
    default int updateByCompositeKey(RspuScene entity) {
        return update(entity, new QueryWrapper<RspuScene>()
            .eq("rspu_id", entity.getRspuId())
            .eq("scene_code", entity.getSceneCode()));
    }

    /**
     * 按复合主键 (rspu_id, scene_code) 删除单条关联。
     *
     * @param rspuId    RSPU ID
     * @param sceneCode 场景字典码
     * @return 影响行数
     */
    default int deleteByCompositeKey(String rspuId, String sceneCode) {
        return delete(new QueryWrapper<RspuScene>()
            .eq("rspu_id", rspuId)
            .eq("scene_code", sceneCode));
    }
}
