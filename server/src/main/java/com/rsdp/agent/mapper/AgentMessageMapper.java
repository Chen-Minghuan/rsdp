package com.rsdp.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.agent.entity.AgentMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 营销 Agent 消息 Mapper。
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {
}
