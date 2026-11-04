package com.healix.agent.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.agent.archive.CreateArchiveDraft;
import com.healix.agent.archive.CreateArchiveParser;
import org.junit.jupiter.api.Test;

class CreateArchiveSkillMatchTest {

    @Test
    void keywordStillMatches() {
        assertTrue(CreateArchiveSkill.matchesMessage("帮我建档"));
        assertTrue(CreateArchiveSkill.matchesMessage("新建患者 李敏"));
    }

    @Test
    void pasteFourFieldsWithoutKeyword() {
        assertTrue(CreateArchiveSkill.matchesMessage("周启明 男 1978-03-12 13812345678"));
        assertTrue(
                CreateArchiveSkill.matchesMessage(
                        "姓名 陈建国\n性别 男\n生日 1965-11-08\n本人手机 13700001122"));
    }

    @Test
    void namePlusMobileIsEnough() {
        assertTrue(CreateArchiveSkill.matchesMessage("赵雨桐 13655556677"));
    }

    @Test
    void plainClinicalChatDoesNotMatch() {
        assertFalse(CreateArchiveSkill.matchesMessage("陈建国今天血压有点高"));
        assertFalse(CreateArchiveSkill.matchesMessage("男 头晕 想做随访"));
        assertFalse(CreateArchiveSkill.matchesMessage("请根据档案给出下一步建议"));
    }

    @Test
    void chipStartIsFreshWithoutIdentityFields() {
        String chip = "帮我建档，请告诉我需要哪些信息";
        CreateArchiveDraft current = CreateArchiveParser.parse(chip);
        assertTrue(CreateArchiveSkill.isFreshArchiveStart(chip, current));
        assertTrue(CreateArchiveSkill.isFreshArchiveStart("帮我建档", CreateArchiveParser.parse("帮我建档")));
        assertTrue(CreateArchiveSkill.isFreshArchiveStart("重新建档", CreateArchiveParser.parse("重新建档")));
    }

    @Test
    void followUpWithFieldsIsNotFresh() {
        CreateArchiveDraft withName = CreateArchiveParser.parse("李敏 女");
        assertFalse(CreateArchiveSkill.isFreshArchiveStart("李敏 女", withName));
        CreateArchiveDraft withMobile = CreateArchiveParser.parse("13800138000");
        assertFalse(CreateArchiveSkill.isFreshArchiveStart("13800138000", withMobile));
    }
}
