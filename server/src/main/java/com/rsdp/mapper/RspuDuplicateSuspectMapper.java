package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RspuDuplicateSuspect;
import org.apache.ibatis.annotations.Mapper;

/**
 * RSPU 疑似同款配对记录 Mapper。
 */
@Mapper
public interface RspuDuplicateSuspectMapper extends BaseMapper<RspuDuplicateSuspect> {
}
