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
}
