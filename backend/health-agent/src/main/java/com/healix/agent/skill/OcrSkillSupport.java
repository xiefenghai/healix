package com.healix.agent.skill;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 检验 / 检查 / 用药 OCR Skill 共用工具（解码、关键词、提示截断）。 */
final class OcrSkillSupport {

    private OcrSkillSupport() {}

    static byte[] decodeImage(String imageBase64) {
        String raw = imageBase64.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        return Base64.getDecoder().decode(raw);
    }

    static String mimeOrJpeg(String mime) {
        return StringUtils.hasText(mime) ? mime : "image/jpeg";
    }

    static boolean containsAny(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 取 warnings 前三条拼成「提示：…」；无则 null。 */
    static String warningsHint(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        int n = Math.min(3, warnings.size());
        return "提示：" + String.join("；", warnings.subList(0, n));
    }
}
