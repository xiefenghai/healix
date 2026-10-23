package com.healix.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.healix.agent.llm.LlmClient.ChatTurn;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaffSessionStoreTest {

    @Test
    void toChatTurns_pairsUserAndAi() {
        List<ChatTurn> turns = StaffSessionStore.toChatTurns(List.of(
                UserMessage.from("任务太多了"),
                AiMessage.from("已按你的要求精简执行任务"),
                UserMessage.from("再少一点运动"),
                AiMessage.from("已调低运动强度")));
        assertEquals(2, turns.size());
        assertEquals("任务太多了", turns.get(0).user());
        assertEquals("已按你的要求精简执行任务", turns.get(0).assistant());
        assertEquals("再少一点运动", turns.get(1).user());
    }
}
