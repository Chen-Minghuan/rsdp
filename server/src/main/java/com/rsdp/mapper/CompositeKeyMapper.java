package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.io.Serializable;

/**
 * 复合主键表 Mapper 基接口。
 *
 * <p>适用于数据库主键为多列复合、MyBatis-Plus 无法表达真实主键的表
 * （实体仅将第一列标注为伪 {@code @TableId}，如 category_dict / rspu_style / rspu_scene）。
 * 此类表禁止使用 {@code updateById}/{@code deleteById}/{@code selectById}：
 * 它们只按伪主键单列生成 WHERE，会误更新/误删/误读同组全部行。
 * 请改用各 Mapper 提供的 {@code updateByCompositeKey}/{@code deleteByCompositeKey}/
 * {@code selectByCompositeKey}，或自行按复合条件构造 Wrapper。</p>
 *
 * <p>实现说明：{@code deleteById(Serializable)} 等便捷重载在 BaseMapper 中
 * 最终均委托 {@code deleteById(T entity)}，在此拦截即可阻断全部按 ID 写路径。</p>
 *
 * @param <T> 实体类型
 */
public interface CompositeKeyMapper<T> extends BaseMapper<T> {

    /**
     * 禁用：复合主键表不支持按伪主键单列更新（会误更新同组全部行）。
     *
     * @param entity 实体
     * @return 永不返回，固定抛出异常
     */
    @Override
    default int updateById(T entity) {
        throw new UnsupportedOperationException(
            "复合主键表禁止使用 updateById（伪 @TableId 只生成单列 WHERE，会误更新同组全部行），请使用 updateByCompositeKey 或按复合条件构造 UpdateWrapper");
    }

    /**
     * 禁用：复合主键表不支持按伪主键单列删除（会误删同组全部行）。
     *
     * @param entity 实体
     * @return 永不返回，固定抛出异常
     */
    @Override
    default int deleteById(T entity) {
        throw new UnsupportedOperationException(
            "复合主键表禁止使用 deleteById（伪 @TableId 只生成单列 WHERE，会误删同组全部行），请使用 deleteByCompositeKey 或按复合条件构造 QueryWrapper");
    }

    /**
     * 禁用：复合主键表不支持按伪主键单列查询（返回同组任意一行，语义错误）。
     *
     * @param id 主键值
     * @return 永不返回，固定抛出异常
     */
    @Override
    default T selectById(Serializable id) {
        throw new UnsupportedOperationException(
            "复合主键表禁止使用 selectById（伪 @TableId 只匹配单列，返回同组任意一行），请使用 selectByCompositeKey 或按复合条件构造 QueryWrapper");
    }
}
