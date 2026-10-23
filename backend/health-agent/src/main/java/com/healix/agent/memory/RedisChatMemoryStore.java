package com.healix.agent.memory;

import com.healix.common.constant.HealthConstants;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LangChain4j {@link ChatMemoryStore} 的 Redis 实现。
 * 消息用官方 {@link ChatMessageSerializer} 序列化，与 MessageWindowChatMemory 配套。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String raw = redisTemplate.opsForValue().get(key(memoryId));
        if (!StringUtils.hasText(raw)) {
            return new ArrayList<>();
        }
        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(raw);
            return messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        } catch (Exception e) {
            log.warn("[ChatMemory] deserialize failed memoryId={} err={}", memoryId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = key(memoryId);
        if (messages == null || messages.isEmpty()) {
            redisTemplate.delete(key);
            return;
        }
        redisTemplate.opsForValue().set(key, ChatMessageSerializer.messagesToJson(messages), TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private static String key(Object memoryId) {
        return HealthConstants.REDIS_LC4J_CHAT_MEMORY_PREFIX + String.valueOf(memoryId);
    }
}
