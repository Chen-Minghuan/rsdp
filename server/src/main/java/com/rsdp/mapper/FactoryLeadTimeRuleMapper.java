package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.FactoryLeadTimeRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FactoryLeadTimeRuleMapper extends BaseMapper<FactoryLeadTimeRule> {

    /**
     * 查询某工厂所有生效的交期规则，按优先级排序。
     */
    @Select("""
        SELECT * FROM factory_lead_time_rule
        WHERE factory_code = #{factoryCode}
          AND status = 'active'
        ORDER BY priority ASC,
                 CASE WHEN category_code IS NOT NULL THEN 0 ELSE 1 END,
                 CASE WHEN material_grade_code IS NOT NULL THEN 0 ELSE 1 END,
                 CASE WHEN process_type = 'standard' THEN 1 ELSE 0 END
        """)
    List<FactoryLeadTimeRule> selectActiveRulesByFactory(@Param("factoryCode") String factoryCode);
}
