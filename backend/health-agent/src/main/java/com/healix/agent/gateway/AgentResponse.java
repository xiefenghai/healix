package com.healix.agent.gateway;

import java.util.List;
import java.util.Map;

public record AgentResponse(
        String sessionId,
        String capability,
        String intent,
        String reply,
        List<AgentAction> actions,
        List<Map<String, Object>> safetyFlags,
        Object extracted) {}
