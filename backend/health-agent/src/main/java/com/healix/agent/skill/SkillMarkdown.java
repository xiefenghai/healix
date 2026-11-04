package com.healix.agent.skill;

import org.springframework.util.StringUtils;

/**
 * Skill 文档工具。YAML frontmatter（{@code ---}…{@code ---}）是 Cursor/本地元数据，
 * 喂给 LLM 前应剥掉，避免占 token。
 */
public final class SkillMarkdown {

    private SkillMarkdown() {}

    /** 去掉开头 YAML frontmatter，保留正文。无 frontmatter 则原样返回。 */
    public static String forLlm(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return markdown == null ? "" : markdown;
        }
        String s = markdown;
        // 去掉 UTF-8 BOM
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        if (!s.startsWith("---")) {
            return s.trim();
        }
        // 仅当首行是 --- 时才当 frontmatter（避免误伤正文里的水平线）
        int firstNl = s.indexOf('\n');
        if (firstNl < 0) {
            return s.trim();
        }
        String firstLine = s.substring(0, firstNl).trim();
        if (!"---".equals(firstLine)) {
            return s.trim();
        }
        int end = s.indexOf("\n---", firstNl);
        if (end < 0) {
            return s.trim();
        }
        int after = end + "\n---".length();
        if (after < s.length() && s.charAt(after) == '\r') {
            after++;
        }
        if (after < s.length() && s.charAt(after) == '\n') {
            after++;
        }
        return s.substring(after).trim();
    }
}
