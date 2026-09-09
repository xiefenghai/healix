package com.healix.agent.ocr;

import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.common.exception.BusinessException;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class VisionOcrTextProvider implements OcrTextProvider {

    private final LlmClient llmClient;

    @Override
    public String extractText(byte[] imageBytes, String mimeType) {
        if (!llmClient.isEnabled()) {
            throw new BusinessException("OCR 服务未启用，请配置 healix.agent.enabled=true 及 API Key");
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String system = "你是 OCR 助手，只输出化验单上的原始文字，按阅读顺序逐行输出，不要解释、不要 JSON。";
        String user = "请提取这张检验/化验单上的全部文字。";
        LlmResponse response = llmClient.chatWithImage("ocr-text", system, user, base64, mimeType);
        if (!response.fromLlm() || !StringUtils.hasText(response.content())) {
            throw new BusinessException("检验单文字识别失败，请检查图片清晰度或 OCR 识图配置");
        }
        return response.content().trim();
    }
}
