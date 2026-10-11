package com.healix.agent.memory;

import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.common.constant.HealthConstants;
import com.healix.common.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StaffSessionStore {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public List<ChatTurn> loadTurns(String sessionId) {
        String raw = redisTemplate.opsForValue().get(key(sessionId));
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        SessionPayload payload = JsonUtils.fromJson(raw, SessionPayload.class);
        if (payload == null || payload.turns == null) {
            return List.of();
        }
        return payload.turns.stream().map(t -> new ChatTurn(t.user, t.assistant)).toList();
    }

    public void appendTurn(String sessionId, String userMessage, String assistantReply) {
        List<Turn> turns = new ArrayList<>();
        String raw = redisTemplate.opsForValue().get(key(sessionId));
        if (raw != null && !raw.isBlank()) {
            SessionPayload existing = JsonUtils.fromJson(raw, SessionPayload.class);
            if (existing != null && existing.turns != null) {
                turns.addAll(existing.turns);
            }
        }
        turns.add(new Turn(userMessage, assistantReply));
        while (turns.size() > HealthConstants.DEFAULT_STAFF_AGENT_SESSION_TURNS) {
            turns.remove(0);
        }
        redisTemplate.opsForValue().set(key(sessionId), JsonUtils.toJson(new SessionPayload(turns)), TTL);
    }

    private static String key(String sessionId) {
        return HealthConstants.REDIS_STAFF_AGENT_SESSION_PREFIX + sessionId;
    }

    private record SessionPayload(List<Turn> turns) {}

    private record Turn(String user, String assistant) {}
}
