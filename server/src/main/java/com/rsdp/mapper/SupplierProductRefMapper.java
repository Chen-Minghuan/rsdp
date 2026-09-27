package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SupplierProductRef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code supplier_product_ref} Mapper（设计文档 v3.3 §6）。
 */
@Mapper
public interface SupplierProductRefMapper extends BaseMapper<SupplierProductRef> {

    /**
     * 按 Identity 唯一键 {@code (factory_code, source_namespace, normalized_source_product_code)} 查询。
     *
     * @param factoryCode 工厂编码
     * @param sourceNamespace 编码命名空间
     * @param normalizedCode 归一后的源商品编码
     * @return 命中的 Supplier Ref，不存在返回 null
     */
    @Select("""
        SELECT * FROM supplier_product_ref
        WHERE factory_code = #{factoryCode}
          AND source_namespace = #{sourceNamespace}
          AND normalized_source_product_code = #{normalizedCode}
        """)
    SupplierProductRef selectByIdentity(@Param("factoryCode") String factoryCode,
                                        @Param("sourceNamespace") String sourceNamespace,
                                        @Param("normalizedCode") String normalizedCode);

    /**
     * 扫描存量 distinct 源编码身份行（Normalizer 升级 Dry Run 第一步，§7）。
     *
     * <p>只取身份与归一相关列；同一 {@code (factory_code, source_namespace, source_product_code)}
     * 组合只出现一次。</p>
     *
     * @return 存量 distinct 身份行
     */
    @Select("""
        SELECT DISTINCT factory_code, source_namespace,
               source_product_code, normalized_source_product_code, code_normalizer_version
        FROM supplier_product_ref
        """)
    List<SupplierProductRef> selectDistinctIdentityCodes();

    /**
     * 统计 normalizer 版本缺失或落后于指定版本的记录数。
     *
     * <p>迁移完成条件为 {@code zero conflict + zero stale normalizer version}（§7 第五步），
     * 本方法提供「zero stale」的核验入口。</p>
     *
     * @param currentVersion 当前活跃规则集版本（字符串形式，与列类型一致）
     * @return 版本为空或非当前版本的记录数
     */
    @Select("""
        SELECT COUNT(*) FROM supplier_product_ref
        WHERE code_normalizer_version IS NULL
           OR code_normalizer_version <> #{currentVersion}
        """)
    long countStaleOrMissingNormalizerVersion(@Param("currentVersion") String currentVersion);
}
