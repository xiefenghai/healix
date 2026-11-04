package com.healix.agent.memory;

import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.common.constant.HealthConstants;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * C 端患者短期会话记忆：LangChain4j {@link MessageWindowChatMemory} + Redis。
 */
@Component
@RequiredArgsConstructor
public class ChatMemoryStore {

    private final RedisChatMemoryStore chatMemoryStore;

    public List<ChatTurn> loadTurns(String userId) {
        return StaffSessionStore.toChatTurns(memory(userId).messages());
    }

    /** 兼容旧调用：压成「U: … | A: …」摘要块，供 prompt 快照等使用。 */
    public List<String> loadRecentSummaries(String userId) {
        List<String> out = new ArrayList<>();
        for (ChatTurn turn : loadTurns(userId)) {
            out.add("U: " + truncate(turn.user()) + " | A: " + truncate(turn.assistant()));
        }
        return out;
    }

    public void appendTurn(String userId, String userMessage, String assistantReply) {
        ChatMemory memory = memory(userId);
        if (StringUtils.hasText(userMessage)) {
            memory.add(UserMessage.from(userMessage.trim()));
        }
        if (StringUtils.hasText(assistantReply)) {
            memory.add(AiMessage.from(assistantReply.trim()));
        }
    }

    public List<String> asContextBlock(String userId) {
        return new ArrayList<>(loadRecentSummaries(userId));
    }

    public void clear(String userId) {
        memory(userId).clear();
    }

    private ChatMemory memory(String userId) {
        int maxMessages = Math.max(4, HealthConstants.DEFAULT_CHAT_MEMORY_TURNS * 2);
        return MessageWindowChatMemory.builder()
                .id(patientMemoryId(userId))
                .maxMessages(maxMessages)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    private static String patientMemoryId(String userId) {
        return "patient:" + userId;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
