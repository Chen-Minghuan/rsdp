package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SchemeCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 推荐候选清单 Mapper。
 */
@Mapper
public interface SchemeCandidateMapper extends BaseMapper<SchemeCandidate> {

    /**
     * 根据推荐请求 ID 查询候选清单。
     *
     * @param recommendRequestId 推荐请求 ID
     * @return 候选列表
     */
    List<SchemeCandidate> selectByRequestId(@Param("recommendRequestId") String recommendRequestId);

    /**
     * 批量插入候选。
     *
     * @param candidates 候选列表
     * @return 插入行数
     */
    int insertBatch(@Param("list") List<SchemeCandidate> candidates);
}
