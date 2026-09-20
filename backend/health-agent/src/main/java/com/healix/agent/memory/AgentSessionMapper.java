package com.healix.agent.memory;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentSessionMapper {
    int insert(AgentSessionEntity row);

    AgentSessionEntity findById(@Param("id") String id);

    AgentSessionEntity findActive(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("staffId") String staffId,
            @Param("peopleId") String peopleId,
            @Param("agentType") String agentType);

    List<AgentSessionEntity> listByStaff(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("staffId") String staffId,
            @Param("peopleId") String peopleId,
            @Param("agentType") String agentType,
            @Param("limit") int limit);

    int touch(@Param("id") String id);

    int close(@Param("id") String id);

    int closeActive(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("staffId") String staffId,
            @Param("peopleId") String peopleId,
            @Param("agentType") String agentType);

    int reactivate(@Param("id") String id);

    int updateTitle(@Param("id") String id, @Param("title") String title);
}
