package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RskuSupply;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RskuSupplyMapper extends BaseMapper<RskuSupply> {

    /**
     * 批量插入 RSKU 报价。
     *
     * @param rskus 报价列表
     * @return 插入行数
     */
    int insertBatch(@Param("rskus") List<RskuSupply> rskus);

    /**
     * 批量插入（空集合安全）。
     *
     * @param rskus 报价列表
     * @return 插入行数
     */
    default int insertBatchSafe(List<RskuSupply> rskus) {
        if (rskus == null || rskus.isEmpty()) {
            return 0;
        }
        return insertBatch(rskus);
    }

    /**
     * 查询指定 RSPU 列表中工厂具备对应产品等级能力的 RSKU。
     *
     * <p>过滤条件：</p>
     * <ul>
     *   <li>RSKU 未软删除</li>
     *   <li>关联工厂未软删除</li>
     *   <li>product_level 为空，或工厂能力表包含该等级</li>
     * </ul>
     *
     * @param rspuIds RSPU ID 列表
     * @return 具备能力的 RSKU 列表
     */
    List<RskuSupply> selectCapableByRspuIds(@Param("rspuIds") List<String> rspuIds);

    /**
     * 恢复指定 RSPU 下被级联软删的 RSKU 报价（回收站还原用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE rsku_supply SET deleted_at = NULL WHERE rspu_id = #{rspuId} AND deleted_at IS NOT NULL")
    int restoreByRspuId(String rspuId);

    /**
     * 物理删除指定 RSPU 下的全部 RSKU 报价（回收站彻底删除用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM rsku_supply WHERE rspu_id = #{rspuId}")
    int physicalDeleteByRspuId(String rspuId);

    // ---------------- v3.3 P0-D：Supplier Configuration Identity（§18.2/§32.1） ----------------

    /**
     * 按新 RSKU Identity（§32.1）查询当前有效供应单元。
     *
     * @param variantId                变体 ID
     * @param factoryCode              工厂码
     * @param supplierConfigurationKey 供应配置 Key（CODE:/TEXT:/BASE）
     * @return 命中的未软删 RSKU；未命中返回 null
     */
    @Select("""
        SELECT * FROM rsku_supply
        WHERE variant_id = #{variantId}
          AND factory_code = #{factoryCode}
          AND supplier_configuration_key = #{supplierConfigurationKey}
          AND deleted_at IS NULL
        """)
    RskuSupply selectAliveByConfig(@Param("variantId") String variantId,
                                   @Param("factoryCode") String factoryCode,
                                   @Param("supplierConfigurationKey") String supplierConfigurationKey);

    /**
     * 列出同一 Variant + Factory 下全部未软删 RSKU（Supplier Configuration Resolver
     * 的候选扫描入口，§18.3；按 supplier_configuration_key 升序保证确定性）。
     *
     * @param variantId   变体 ID
     * @param factoryCode 工厂码
     * @return 候选 RSKU 列表
     */
    @Select("""
        SELECT * FROM rsku_supply
        WHERE variant_id = #{variantId}
          AND factory_code = #{factoryCode}
          AND deleted_at IS NULL
        ORDER BY supplier_configuration_key, rsku_id
        """)
    List<RskuSupply> selectAliveByVariantFactory(@Param("variantId") String variantId,
                                                 @Param("factoryCode") String factoryCode);

    /**
     * 行锁读取未软删 RSKU（§34.1「写入前锁定目标实体并在同一事务重读
     * manual_override_fields」与 §18.3 受控 Key Migration 的 SELECT FOR UPDATE）。
     *
     * @param rskuId RSKU ID
     * @return 锁定的行；不存在或已软删返回 null
     */
    @Select("SELECT * FROM rsku_supply WHERE rsku_id = #{rskuId} AND deleted_at IS NULL FOR UPDATE")
    RskuSupply selectAliveByIdForUpdate(@Param("rskuId") String rskuId);

    /**
     * 报价乐观并发更新（§37）：仅当当前 quote_version 等于期望值时
     * {@code quote_version + 1}；影响 0 行即 quote_version_conflict。
     *
     * @param rskuId          RSKU ID
     * @param expectedVersion 期望的当前 quote_version
     * @param updatedAt       更新时间
     * @return 影响行数（0 = 版本冲突或行不存在/已软删）
     */
    @Update("""
        UPDATE rsku_supply
        SET quote_version = quote_version + 1,
            updated_at = #{updatedAt}
        WHERE rsku_id = #{rskuId}
          AND quote_version = #{expectedVersion}
          AND deleted_at IS NULL
        """)
    int bumpQuoteVersion(@Param("rskuId") String rskuId,
                         @Param("expectedVersion") Integer expectedVersion,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 并发安全插入（§38）：撞 {@code uk_rsku_variant_factory_config_alive} 时不报错、
     * 返回 0 行，由调用方转入更新路径——并发导入相同 variant + factory +
     * supplier configuration 依赖数据库唯一约束兜底，且不以失败语句中断当前事务。
     *
     * @param rsku 待插入 RSKU（supplierConfigurationKey 等 v3.3 字段必须显式赋值）
     * @return 实际插入行数（0 = 并发撞键）
     */
    int insertOnConfigConflictDoNothing(@Param("rsku") RskuSupply rsku);

    /**
     * Normalizer 升级 Dry Run 全量扫描（§18.3）：全部未软删 RSKU 的
     * Supplier Configuration 身份相关列，按 (variant_id, factory_code,
     * supplier_configuration_key) 升序保证确定性输出。
     *
     * @return 全部未软删 RSKU（仅身份相关列可靠）
     */
    @Select("""
        SELECT rsku_id, rspu_id, variant_id, factory_code,
               supplier_material_grade_code, supplier_material_grade_text,
               supplier_configuration_key, supplier_configuration_normalizer_version
        FROM rsku_supply
        WHERE deleted_at IS NULL
        ORDER BY variant_id, factory_code, supplier_configuration_key, rsku_id
        """)
    List<RskuSupply> selectAliveForConfigurationScan();
    /**
     * 有效期跨日边界涉及的 RSPU（价格投影每日刷新用）：今天生效或昨天到期的未软删报价。
     *
     * @param today     今天
     * @param yesterday 昨天
     * @return 去重后的 RSPU ID 列表
     */
    @org.apache.ibatis.annotations.Select("SELECT DISTINCT rspu_id FROM rsku_supply"
        + " WHERE deleted_at IS NULL"
        + "   AND (effective_from = #{today} OR effective_to = #{yesterday})")
    List<String> selectRspuIdsWithValidityBoundary(@org.apache.ibatis.annotations.Param("today") java.time.LocalDate today,
                                                   @org.apache.ibatis.annotations.Param("yesterday") java.time.LocalDate yesterday);
}
