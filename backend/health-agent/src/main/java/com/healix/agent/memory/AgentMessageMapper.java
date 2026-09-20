package com.healix.agent.memory;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMessageMapper {
    int insert(AgentMessageEntity row);

    List<AgentMessageEntity> listBySession(
            @Param("sessionId") String sessionId, @Param("limit") int limit);
    int countBySession(@Param("sessionId") String sessionId);

    String lastPreview(@Param("sessionId") String sessionId);

    /** 软删某会话下指定 tool_name 的历史消息（如旧简报）。 */
    int softDeleteBySessionAndTool(
            @Param("sessionId") String sessionId, @Param("toolName") String toolName);
}
