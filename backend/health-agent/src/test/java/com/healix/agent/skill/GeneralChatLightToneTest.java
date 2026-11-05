package com.healix.agent.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.healix.agent.skill.GeneralChatSkill.LightTone;
import com.healix.agent.skill.GeneralChatSkill.OrgGuideIntent;
import org.junit.jupiter.api.Test;

class GeneralChatLightToneTest {

    @Test
    void ackWords() {
        assertEquals(LightTone.ACK, GeneralChatSkill.classifyLightTone("嗯"));
        assertEquals(LightTone.ACK, GeneralChatSkill.classifyLightTone("好的"));
        assertEquals(LightTone.ACK, GeneralChatSkill.classifyLightTone("收到"));
        assertEquals(LightTone.ACK, GeneralChatSkill.classifyLightTone("OK"));
    }

    @Test
    void declineWords() {
        assertEquals(LightTone.DECLINE, GeneralChatSkill.classifyLightTone("不要"));
        assertEquals(LightTone.DECLINE, GeneralChatSkill.classifyLightTone("不用了"));
        assertEquals(LightTone.DECLINE, GeneralChatSkill.classifyLightTone("算了"));
        assertEquals(LightTone.DECLINE, GeneralChatSkill.classifyLightTone("先不安排"));
    }

    @Test
    void gibberishDigitsAndRepeats() {
        assertEquals(LightTone.GIBBERISH, GeneralChatSkill.classifyLightTone("21212121"));
        assertEquals(LightTone.GIBBERISH, GeneralChatSkill.classifyLightTone("10264"));
        assertEquals(LightTone.GIBBERISH, GeneralChatSkill.classifyLightTone("aaaa"));
    }

    @Test
    void realAsksStayNone() {
        assertEquals(LightTone.NONE, GeneralChatSkill.classifyLightTone("今天先忙什么"));
        assertEquals(LightTone.NONE, GeneralChatSkill.classifyLightTone("帮我建档"));
        assertEquals(LightTone.NONE, GeneralChatSkill.classifyLightTone("张三 女 1990-05-01 13800138000"));
    }

    @Test
    void capabilityAndNextStepIntents() {
        assertEquals(OrgGuideIntent.CAPABILITY, GeneralChatSkill.classifyOrgGuideIntent("你能干什么"));
        assertEquals(OrgGuideIntent.CAPABILITY, GeneralChatSkill.classifyOrgGuideIntent("你能做点啥"));
        assertEquals(OrgGuideIntent.CAPABILITY, GeneralChatSkill.classifyOrgGuideIntent("你会什么"));
        assertEquals(OrgGuideIntent.NEXT_STEP, GeneralChatSkill.classifyOrgGuideIntent("我现在要干什么"));
        assertEquals(OrgGuideIntent.NEXT_STEP, GeneralChatSkill.classifyOrgGuideIntent("今天先忙什么"));
        assertEquals(OrgGuideIntent.TASK_TYPES, GeneralChatSkill.classifyOrgGuideIntent("解释任务类型 你解释一下"));
        assertEquals(OrgGuideIntent.NONE, GeneralChatSkill.classifyOrgGuideIntent("帮我建档"));
    }

    @Test
    void dataEntryHowToIntent() {
        assertEquals(
                OrgGuideIntent.DATA_ENTRY,
                GeneralChatSkill.classifyOrgGuideIntent("建档后怎么录入体检和检验数据？"));
        assertEquals(OrgGuideIntent.DATA_ENTRY, GeneralChatSkill.classifyOrgGuideIntent("怎么录入体检数据"));
        assertEquals(OrgGuideIntent.DATA_ENTRY, GeneralChatSkill.classifyOrgGuideIntent("检验单在哪里上传"));
        assertEquals(OrgGuideIntent.DATA_ENTRY, GeneralChatSkill.classifyOrgGuideIntent("单据录入入口在哪"));
        assertEquals(OrgGuideIntent.NONE, GeneralChatSkill.classifyOrgGuideIntent("今天血压怎么样"));
    }

    @Test
    void createCareTeamIntent() {
        assertEquals(
                OrgGuideIntent.CREATE_CARE_TEAM, GeneralChatSkill.classifyOrgGuideIntent("创建健管组"));
        assertEquals(
                OrgGuideIntent.CREATE_CARE_TEAM,
                GeneralChatSkill.classifyOrgGuideIntent("帮我再创建健管组 名字 老板组"));
        assertEquals(
                OrgGuideIntent.CREATE_CARE_TEAM, GeneralChatSkill.classifyOrgGuideIntent("新建一个健管组"));
        assertEquals(
                OrgGuideIntent.CREATE_CARE_TEAM, GeneralChatSkill.classifyOrgGuideIntent("怎么创建健管组"));
        assertEquals(
                OrgGuideIntent.CREATE_CARE_TEAM, GeneralChatSkill.classifyOrgGuideIntent("在哪里创建"));
        // 解释「为什么」不能当创建动作短路
        assertEquals(OrgGuideIntent.NONE, GeneralChatSkill.classifyOrgGuideIntent("为什么要创建健管组"));
        assertEquals(OrgGuideIntent.NONE, GeneralChatSkill.classifyOrgGuideIntent("创建健管组有什么用"));
        assertEquals(true, GeneralChatSkill.isCareTeamExplainQuestion("为什么要创建健管组".replaceAll("\\s+", "")));
    }

    @Test
    void filterOrgBriefActionsDropsPatientChipsWhenIrrelevant() {
        var patient = com.healix.agent.gateway.AgentAction.focusPatient("苏苏", "p1");
        var create = com.healix.agent.gateway.AgentAction.createCareTeam("去创建健管组", null);
        var filtered =
                GeneralChatSkill.filterOrgBriefActions(java.util.List.of(patient, create), "在哪里创建");
        assertEquals(1, filtered.size());
        assertEquals("NAVIGATE", filtered.get(0).type());

        var kept =
                GeneralChatSkill.filterOrgBriefActions(
                        java.util.List.of(patient, create), "今天先忙什么");
        assertEquals(2, kept.size());
    }

    @Test
    void parseCareTeamNameFromMessage() {
        assertEquals("老板组", GeneralChatSkill.parseCareTeamName("帮我再创建健管组 名字 老板组"));
        assertEquals("老板组", GeneralChatSkill.parseCareTeamName("创建健管组叫老板组"));
        assertEquals("老板组", GeneralChatSkill.parseCareTeamName("创建健管组：老板组"));
        assertEquals("老板组", GeneralChatSkill.parseCareTeamName("新建健管组 老板组"));
        assertEquals(null, GeneralChatSkill.parseCareTeamName("创建健管组"));
        assertEquals(null, GeneralChatSkill.parseCareTeamName("帮我建档"));
    }
}
