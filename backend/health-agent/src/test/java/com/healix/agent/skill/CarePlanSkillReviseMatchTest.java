package com.healix.agent.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CarePlanSkillReviseMatchTest {

    @Test
    void matchesTaskVolumeFeedback() {
        assertTrue(CarePlanSkill.matchesReviseMessage("任务太多了"));
        assertTrue(CarePlanSkill.matchesReviseMessage("请精简待办"));
        assertTrue(CarePlanSkill.matchesMessage("任务太多了"));
        assertFalse(CarePlanSkill.matchesReviseMessage("重新生成方案"));
    }
}
