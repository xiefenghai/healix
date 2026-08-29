package com.healix.agent.memory;

import com.healix.common.constant.HealthConstants;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Short-term conversation memory backed by Redis (last N turns).
 */
@Component
@RequiredArgsConstructor
public class ChatMemoryStore {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public List<String> loadRecentSummaries(String userId) {
        String key = key(userId);
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return List.of();
        }
        long start = Math.max(0, size - HealthConstants.DEFAULT_CHAT_MEMORY_TURNS);
        List<String> range = redisTemplate.opsForList().range(key, start, -1);
        return range != null ? range : List.of();
    }

    public void appendTurn(String userId, String userMessage, String assistantReply) {
        String key = key(userId);
        String summary = "U: " + truncate(userMessage) + " | A: " + truncate(assistantReply);
        redisTemplate.opsForList().rightPush(key, summary);
        redisTemplate.expire(key, TTL);

        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > HealthConstants.DEFAULT_CHAT_MEMORY_TURNS * 2L) {
            redisTemplate.opsForList().trim(key, -HealthConstants.DEFAULT_CHAT_MEMORY_TURNS, -1);
        }
    }

    public List<String> asContextBlock(String userId) {
        return new ArrayList<>(loadRecentSummaries(userId));
    }

    private static String key(String userId) {
        return HealthConstants.REDIS_CHAT_MEMORY_PREFIX + userId;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
