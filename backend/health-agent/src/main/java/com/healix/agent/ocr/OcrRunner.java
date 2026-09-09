package com.healix.agent.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.support.AiUsageGuard;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * 单据 OCR 的公共执行链：图片校验 → 按模式取文本或直传图片 → LLM → JSON。
 *
 * <p>检验单与检查单只在 skill 与抽取字段上不同，这里收口图片限制、
 * VISION/TEXT_LLM 模式切换与返回值解析，避免两份实现漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrRunner {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    private final LlmClient llmClient;
    private final OcrTextProvider ocrTextProvider;
    private final ResourceLoader resourceLoader;
    private final AiUsageGuard aiUsageGuard;

    @Value("${healix.agent.ocr.mode:TEXT_LLM}")
    private String ocrMode;

    /**
     * @param skill skill 目录名，如 {@code ocr-exam}
     * @param failureMessage LLM 无返回时抛给用户的提示
     */
    public JsonNode recognizeJson(
            String skill,
            String userPrompt,
            byte[] imageBytes,
            String mimeType,
            String fallbackSystemPrompt,
            String failureMessage) {
        validateImage(imageBytes, mimeType);
        aiUsageGuard.checkCurrent(FeatureFlagKeyEnum.AI_OCR, QuotaKeyEnum.OCR_MONTHLY);
        if (!llmClient.isEnabled()) {
            throw new BusinessException("OCR 服务未启用，请配置 healix.agent.enabled=true 及 API Key");
        }
        String systemPrompt = loadSkillPrompt(skill, fallbackSystemPrompt);
        String mode = ocrMode == null ? "TEXT_LLM" : ocrMode.trim().toUpperCase(Locale.ROOT);
        log.info("[OCR] recognize start skill={} mode={} imageBytes={} mime={}", skill, mode, imageBytes.length, mimeType);

        LlmResponse llm;
        if ("VISION".equals(mode)) {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            llm = llmClient.chatWithImage(skill, systemPrompt, userPrompt, base64, mimeType);
        } else {
            String text = ocrTextProvider.extractText(imageBytes, mimeType);
            llm = llmClient.chat(skill, systemPrompt, userPrompt + "\n\nOCR 文本：\n" + text, List.of());
        }
        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            String hint = llm.completionTokens() != null && llm.completionTokens() >= 4000
                    ? "（模型输出达到 token 上限，请检查日志中的 rawResponse）"
                    : "";
            throw new BusinessException(failureMessage + hint);
        }
        return parseJson(llm.content());
    }

    public void validateImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("请上传图片");
        }
        if (imageBytes.length > MAX_BYTES) {
            throw new BusinessException("图片大小不能超过 5MB");
        }
        if (!ALLOWED_MIME.contains(normalizeMime(mimeType))) {
            throw new BusinessException("仅支持 JPG、PNG、WEBP 图片");
        }
    }

    private static String normalizeMime(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return "image/jpeg";
        }
        return mimeType.trim().toLowerCase(Locale.ROOT);
    }

    /** 模型偶尔会把 JSON 包在代码块里，按首尾花括号截取。 */
    public static JsonNode parseJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
        }
        try {
            return JsonUtils.mapper().readTree(trimmed);
        } catch (Exception e) {
            throw new BusinessException("识别结果解析失败");
        }
    }

    private String loadSkillPrompt(String skill, String fallback) {
        try {
            Resource res = resourceLoader.getResource("classpath:skills/" + skill + "/SKILL.md");
            if (res.exists()) {
                return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("skill {} missing: {}", skill, e.getMessage());
        }
        return fallback;
    }
}
