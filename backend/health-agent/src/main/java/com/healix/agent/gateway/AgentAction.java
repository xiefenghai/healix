package com.healix.agent.gateway;

import java.util.Map;

public record AgentAction(String type, String label, String path, Map<String, Object> payload) {

    public static AgentAction navigate(String label, String path) {
        return new AgentAction("NAVIGATE", label, path, null);
    }

}
