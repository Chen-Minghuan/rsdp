package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.FactoryProductCapability;
import com.rsdp.dto.FactoryCapabilitySource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工厂产品能力档案 Mapper。
 */
@Mapper
public interface FactoryProductCapabilityMapper extends BaseMapper<FactoryProductCapability> {

    /**
     * 根据工厂编码删除所有能力行。
     *
     * @param factoryCode 工厂编码
     * @return 删除行数
     */
    int deleteByFactoryCode(@Param("factoryCode") String factoryCode);

    /**
     * 批量插入能力行（忽略唯一冲突）。
     *
     * @param capabilities 能力行列表
     * @return 插入行数
     */
    int insertBatchIgnoreConflict(@Param("list") List<FactoryProductCapability> capabilities);

    /**
     * 查询指定工厂当前有效 RSKU 对应的品类/风格/材质组合。
     *
     * @param factoryCode 工厂编码
     * @return 能力源数据列表
     */
    List<FactoryCapabilitySource> selectCapabilitySourcesByFactory(@Param("factoryCode") String factoryCode);
}
