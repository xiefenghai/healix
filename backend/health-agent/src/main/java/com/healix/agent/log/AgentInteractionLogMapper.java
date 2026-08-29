package com.healix.agent.log;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentInteractionLogMapper {

    int insert(AgentInteractionLog log);
}
