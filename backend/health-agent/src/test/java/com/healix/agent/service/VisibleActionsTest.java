package com.healix.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.agent.planner.IntentRouter;
import com.healix.agent.tool.AgentQuickAction;
import com.healix.common.constant.IntentType;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisibleActionsTest {

    private final IntentRouter router = new IntentRouter();

    @Test
    void todayTaskKeepsCheckinButtonsOnlyWhenOffered() {
        List<AgentQuickAction> actions = List.of(
                new AgentQuickAction("去打卡", "/care-plan"),
                new AgentQuickAction("去服药打卡", "/medications"),
                new AgentQuickAction("查看方案", "/care-plan"));
        List<AgentQuickAction> visible = HealthAgentService.visibleActions(IntentType.QUERY_PLAN, actions);
        assertEquals(List.of("去打卡", "去服药打卡"), visible.stream().map(AgentQuickAction::label).toList());
        assertEquals(IntentType.QUERY_PLAN, router.route("今天还有什么要做"));
    }

    @Test
    void medicationTalkOnlyShowsMedCheckin() {
        List<AgentQuickAction> actions = List.of(
                new AgentQuickAction("去打卡", "/care-plan"),
                new AgentQuickAction("去服药打卡", "/medications"));
        List<AgentQuickAction> visible = HealthAgentService.visibleActions(IntentType.QUERY_MEDICATION, actions);
        assertEquals(List.of("去服药打卡"), visible.stream().map(AgentQuickAction::label).toList());
    }

    @Test
    void vitalsAndChatDropCheckinButtons() {
        List<AgentQuickAction> actions = List.of(
                new AgentQuickAction("去打卡", "/care-plan"),
                new AgentQuickAction("去服药打卡", "/medications"),
                new AgentQuickAction("记一次指标", "/health/record"));
        assertTrue(HealthAgentService.visibleActions(IntentType.QUERY_VITALS, actions).stream()
                .map(AgentQuickAction::label)
                .noneMatch(label -> label.contains("打卡")));
        assertTrue(HealthAgentService.visibleActions(IntentType.CHITCHAT, actions).stream()
                .noneMatch(action -> action.label().contains("打卡")));
        assertEquals(IntentType.QUERY_VITALS, router.route("最近血压怎么样"));
    }
}
