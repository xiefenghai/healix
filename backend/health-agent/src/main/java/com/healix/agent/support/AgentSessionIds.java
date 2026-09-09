package com.healix.agent.support;

import com.healix.common.util.SnowflakeId;
import org.springframework.util.StringUtils;

/** Agent 会话 ID（与库表 session_id VARCHAR(32) 对齐）。 */
public final class AgentSessionIds {

    private AgentSessionIds() {}

    public static String newSessionId() {
        return SnowflakeId.nextBizId();
    }

    public static String normalize(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return newSessionId();
        }
        String id = sessionId.trim();
        if (id.length() > 32) {
            id = id.replace("-", "");
        }
        if (id.length() > 32) {
            return newSessionId();
        }
        return id;
    }
}
