package com.healix.agent.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkillMarkdownTest {

    @Test
    void stripsYamlFrontmatter() {
        String raw =
                """
                ---
                name: management-plan
                display_name: 管理方案生成
                ---

                # 健康管理方案生成 Skill

                正文规则。
                """;
        String out = SkillMarkdown.forLlm(raw);
        assertThat(out).startsWith("# 健康管理方案生成 Skill");
        assertThat(out).doesNotContain("display_name");
        assertThat(out).contains("正文规则");
    }

    @Test
    void leavesBodyWithoutFrontmatter() {
        String raw = "# Hello\n\n规则";
        assertThat(SkillMarkdown.forLlm(raw)).isEqualTo("# Hello\n\n规则");
    }

    @Test
    void ignoresHorizontalRuleInBody() {
        String raw = "# Title\n\n---\n\nmore";
        assertThat(SkillMarkdown.forLlm(raw)).isEqualTo("# Title\n\n---\n\nmore");
    }

    @Test
    void loadsGeneralChatOrgTemplate() {
        String body = SkillMarkdown.loadClasspath("skills/general-chat/org-system.md", "");
        assertThat(body).contains("{{ORG_BRIEF}}");
        assertThat(body).contains("创建健管组");
        assertThat(body).contains("批量分配面板");
        assertThat(body).contains("去创建我的健管组");
        assertThat(body).contains("专属服务小分队");
        assertThat(body).doesNotContain("打底");
        assertThat(body).doesNotContain("name: general-chat-org");
    }

    @Test
    void applyPlaceholder() {
        assertThat(SkillMarkdown.apply("hi {{NAME}}", "NAME", "灵犀")).isEqualTo("hi 灵犀");
    }
}
