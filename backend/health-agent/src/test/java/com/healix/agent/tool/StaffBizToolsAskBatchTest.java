package com.healix.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StaffBizToolsAskBatchTest {

    @Test
    void asksBatchAssign_matchesHowToAndUnassignedPhrases() {
        assertThat(StaffBizTools.asksBatchAssign("有一批患者还没分组，怎么批量分配到健管组？"))
                .isTrue();
        assertThat(StaffBizTools.asksBatchAssign("怎么批量分组？")).isTrue();
        assertThat(StaffBizTools.asksBatchAssign("未分组患者怎么处理")).isTrue();
        assertThat(StaffBizTools.asksBatchAssign("今天天气怎么样")).isFalse();
        assertThat(StaffBizTools.asksBatchAssign(null)).isFalse();
    }

    @Test
    void asksCareTeamGuide_matchesWhyAndHowToGroup() {
        assertThat(StaffBizTools.asksCareTeamGuide("为什么要创建健管组？怎么给患者分组？")).isTrue();
        assertThat(StaffBizTools.asksCareTeamGuide("健管组是干什么的？")).isTrue();
        assertThat(StaffBizTools.asksCareTeamGuide("今天天气怎么样")).isFalse();
        assertThat(StaffBizTools.asksCareTeamGuide(null)).isFalse();
    }

    @Test
    void asksCreateCareTeamIntent_matchesCreateAnotherTeam() {
        assertThat(StaffBizTools.asksCreateCareTeamIntent("再建一个组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("再创建一个健管组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("再创建健管组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("新建健管组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("帮我创建一个健管组")).isTrue();
        // 短口令 / 带组名（新手起步：「建组 健管二组」）
        assertThat(StaffBizTools.asksCreateCareTeamIntent("建组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("建组 健管二组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("帮我建组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("建个组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("建一个组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("创建组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("我要建组")).isTrue();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("怎么建组")).isFalse();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("建组后怎么录入")).isFalse();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("帮我建档")).isFalse();
        assertThat(StaffBizTools.asksCreateCareTeamIntent("今天天气怎么样")).isFalse();
        assertThat(StaffBizTools.asksCreateCareTeamIntent(null)).isFalse();
    }

    @Test
    void extractSuggestedCareTeamName_parsesExplicitName() {
        assertThat(StaffBizTools.extractSuggestedCareTeamName("建组 健管二组"))
                .isEqualTo("健管二组");
        assertThat(StaffBizTools.extractSuggestedCareTeamName("创建健管组 健管二组"))
                .isEqualTo("健管二组");
        assertThat(StaffBizTools.extractSuggestedCareTeamName("新建健管组：减重一组"))
                .isEqualTo("减重一组");
        assertThat(StaffBizTools.extractSuggestedCareTeamName("创建一个健管组叫「慢病组」"))
                .isEqualTo("慢病组");
        assertThat(StaffBizTools.extractSuggestedCareTeamName("帮我创建一个健管组")).isNull();
        assertThat(StaffBizTools.extractSuggestedCareTeamName("今天天气怎么样")).isNull();
    }
}
