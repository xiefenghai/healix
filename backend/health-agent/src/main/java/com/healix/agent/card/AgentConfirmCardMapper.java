package com.healix.agent.card;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConfirmCardMapper {

    int insert(AgentConfirmCard card);

    AgentConfirmCard findById(@Param("id") String id);

    int update(AgentConfirmCard card);

    int expirePending(@Param("peopleId") String peopleId);
}
