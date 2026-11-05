package com.healix.agent.skill;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * Skill 文档工具。YAML frontmatter（{@code ---}…{@code ---}）是 Cursor/本地元数据，
 * 喂给 LLM 前应剥掉，避免占 token。
 */
public final class SkillMarkdown {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private SkillMarkdown() {}

    /**
     * 从 classpath 加载 Skill 正文（剥 frontmatter）；结果按 path 缓存。
     * 文件缺失时返回 {@code fallback}。
     */
    public static String loadClasspath(String classpathRelative, String fallback) {
        if (!StringUtils.hasText(classpathRelative)) {
            return fallback == null ? "" : fallback;
        }
        return CACHE.computeIfAbsent(
                classpathRelative,
                path -> {
                    try {
                        ClassPathResource res = new ClassPathResource(path);
                        if (!res.exists()) {
                            return fallback == null ? "" : fallback;
                        }
                        String raw = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
                        String body = forLlm(raw);
                        return StringUtils.hasText(body) ? body : (fallback == null ? "" : fallback);
                    } catch (Exception e) {
                        return fallback == null ? "" : fallback;
                    }
                });
    }

    /** 简单占位替换：{@code {{KEY}}} → value（value 为 null 时当空串）。 */
    public static String apply(String template, String key, String value) {
        if (!StringUtils.hasText(template) || !StringUtils.hasText(key)) {
            return template == null ? "" : template;
        }
        return template.replace("{{" + key + "}}", value == null ? "" : value);
    }

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
