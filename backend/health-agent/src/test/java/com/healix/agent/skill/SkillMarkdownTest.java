package com.healix.agent.skill;

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
}
