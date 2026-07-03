package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SchemeItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 搭配方案项 Mapper。
 */
@Mapper
public interface SchemeItemMapper extends BaseMapper<SchemeItem> {

    /**
     * 批量插入方案项。
     *
     * @param items 方案项列表
     * @return 插入行数
     */
    int insertBatch(@Param("items") List<SchemeItem> items);

    /**
     * 批量插入方案项（空集合安全）。
     *
     * @param items 方案项列表
     * @return 插入行数
     */
    default int insertBatchSafe(List<SchemeItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return insertBatch(items);
    }
}
