package com.healix.agent.memory;

import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.common.constant.HealthConstants;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 驾驶舱员工会话记忆：底层为 LangChain4j {@link MessageWindowChatMemory} + Redis。
 */
@Component
@RequiredArgsConstructor
public class StaffSessionStore {

    private final RedisChatMemoryStore chatMemoryStore;

    public List<ChatTurn> loadTurns(String sessionId) {
        return toChatTurns(memory(sessionId).messages());
    }

    public void appendTurn(String sessionId, String userMessage, String assistantReply) {
        ChatMemory memory = memory(sessionId);
        if (StringUtils.hasText(userMessage)) {
            memory.add(UserMessage.from(userMessage.trim()));
        }
        if (StringUtils.hasText(assistantReply)) {
            memory.add(AiMessage.from(assistantReply.trim()));
        }
    }

    public void clear(String sessionId) {
        memory(sessionId).clear();
    }

    private ChatMemory memory(String sessionId) {
        // 一轮 = user + ai 两条；窗口按消息条数计
        int maxMessages = Math.max(4, HealthConstants.DEFAULT_STAFF_AGENT_SESSION_TURNS * 2);
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(maxMessages)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /** 把 LC4j 消息压成 Spring AI / LlmClient 用的成对轮次。 */
    static List<ChatTurn> toChatTurns(List<ChatMessage> messages) {
        List<ChatTurn> out = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return out;
        }
        String pendingUser = null;
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage user) {
                if (pendingUser != null) {
                    out.add(new ChatTurn(pendingUser, ""));
                }
                pendingUser = user.singleText();
            } else if (msg instanceof AiMessage ai) {
                String text = ai.text() == null ? "" : ai.text();
                if (pendingUser != null) {
                    out.add(new ChatTurn(pendingUser, text));
                    pendingUser = null;
                } else if (StringUtils.hasText(text)) {
                    out.add(new ChatTurn("", text));
                }
            }
        }
        if (pendingUser != null) {
            out.add(new ChatTurn(pendingUser, ""));
        }
        return out;
    }
}
