package com.healix.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.healix.agent.gateway.AgentAction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneralChatUnassignedActionTest {

    @Test
    void ensureUnassignedWorkbenchAction_keepsOnlyPanelWhenReplyMentionsIt() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.focusPatient("张三 · 需关注", "p1"));

        GeneralChatHandler.ensureUnassignedWorkbenchAction(
                actions, "点下方「打开批量分配面板」一键分配即可。");

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo("NAVIGATE");
        assertThat(actions.get(0).path()).isEqualTo("unassigned-workbench");
        assertThat(actions.get(0).label()).isEqualTo("打开批量分配面板");
        assertThat(actions.get(0).payload()).isNull();
    }

    @Test
    void ensureUnassignedWorkbenchAction_disablesPanelWhenNoTeam() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.createCareTeam("去创建我的健管组", null));

        GeneralChatHandler.ensureUnassignedWorkbenchAction(
                actions, "建组完成后即可使用「批量分配患者」功能。", true);

        assertThat(actions).hasSize(2);
        AgentAction panel =
                actions.stream()
                        .filter(a -> "unassigned-workbench".equals(a.path()))
                        .findFirst()
                        .orElseThrow();
        assertThat(panel.label()).isEqualTo("打开批量分配面板");
        assertThat(panel.payload()).containsEntry("disabled", true);
        assertThat(panel.payload()).containsEntry("disabledReason", "请先创建至少一个健管组");
    }

    @Test
    void ensureUnassignedWorkbenchAction_stripsFocusChipsBesidePanel() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.focusPatient("李四的拉文 · 需关注", "p2"));
        actions.add(AgentAction.openUnassignedWorkbench("打开批量分组面板"));

        GeneralChatHandler.ensureUnassignedWorkbenchAction(actions, "用批量分配面板最省事");

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).path()).isEqualTo("unassigned-workbench");
        assertThat(actions.get(0).label()).isEqualTo("打开批量分配面板");
    }

    @Test
    void ensureUnassignedWorkbenchAction_noopWhenReplySilent() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.focusPatient("王五 · 需关注", "p3"));

        GeneralChatHandler.ensureUnassignedWorkbenchAction(actions, "今天暂无紧急事项。");

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo("FOCUS_PATIENT");
    }

    @Test
    void ensureCreateCareTeamAction_addsMissingCreateButton() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.openUnassignedWorkbench("打开批量分配面板"));

        GeneralChatHandler.ensureCreateCareTeamAction(
                actions, "建议你先点下方「去创建健管组」建一个。");

        assertThat(actions.stream().anyMatch(a -> "create-care-team".equals(a.path()))).isTrue();
        assertThat(
                        actions.stream()
                                .filter(a -> "create-care-team".equals(a.path()))
                                .findFirst()
                                .orElseThrow()
                                .label())
                .isEqualTo("去创建我的健管组");
    }

    @Test
    void ensureCreateCareTeamAction_usesCreateLabelWhenRebuilding() {
        List<AgentAction> actions = new ArrayList<>();

        GeneralChatHandler.ensureCreateCareTeamAction(actions, "可以再建一个组，点下方创建。");

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).path()).isEqualTo("create-care-team");
        assertThat(actions.get(0).label()).isEqualTo("去创建健管组");
    }

    @Test
    void replyMentionsCreateCareTeam_detectsGuidePhrases() {
        assertThat(GeneralChatHandler.replyMentionsCreateCareTeam("点下方「去创建健管组」")).isTrue();
        assertThat(GeneralChatHandler.replyMentionsCreateCareTeam("今天暂无紧急事项")).isFalse();
    }

    @Test
    void alignReplyWithVisibleActions_keepsHintsWhenButtonsPresent() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.createCareTeam("去创建我的健管组", null));
        actions.add(AgentAction.openUnassignedWorkbench("打开批量分配面板"));
        String reply = "请点下方「去创建我的健管组」，再建组；再点下方「打开批量分配面板」。";
        String out = GeneralChatHandler.alignReplyWithVisibleActions(reply, actions);
        assertThat(out).contains("去创建我的健管组");
        assertThat(out).contains("打开批量分配面板");
    }

    @Test
    void alignReplyWithVisibleActions_rewritesMissingCreateButton() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.openUnassignedWorkbench("打开批量分配面板"));
        String reply = "请点本回复下方的「去创建健管组」，再建组。";
        String out = GeneralChatHandler.alignReplyWithVisibleActions(reply, actions);
        assertThat(out).contains("左侧「先创建我的健管组」");
        assertThat(out).doesNotContain("本回复下方的「去创建健管组」");
    }

    @Test
    void prioritizeNoTeamActions_putsCreateBeforePanel() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.openUnassignedWorkbench("打开批量分配面板"));
        actions.add(AgentAction.createCareTeam("去创建我的健管组", null));

        GeneralChatHandler.prioritizeNoTeamActions(actions);

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).path()).isEqualTo("create-care-team");
        assertThat(actions.get(1).path()).isEqualTo("unassigned-workbench");
    }

    @Test
    void lockUnassignedWorkbenchActions_marksDisabled() {
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.createCareTeam("去创建我的健管组", null));
        actions.add(AgentAction.openUnassignedWorkbench("打开批量分组面板"));

        GeneralChatHandler.lockUnassignedWorkbenchActions(actions);

        AgentAction panel = actions.get(1);
        assertThat(panel.label()).isEqualTo("打开批量分配面板");
        assertThat(panel.payload()).containsEntry("disabled", true);
        assertThat(panel.payload()).containsEntry("disabledReason", "请先创建至少一个健管组");
    }
}
