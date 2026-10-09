package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.CadAnalysisFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * CAD 分析人工校正 Diff 反馈 Mapper。
 */
@Mapper
public interface CadAnalysisFeedbackMapper extends BaseMapper<CadAnalysisFeedback> {
}
