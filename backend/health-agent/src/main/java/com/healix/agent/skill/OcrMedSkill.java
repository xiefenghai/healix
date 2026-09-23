package com.healix.agent.skill;

import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.ocr.MedOcrRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.core.medication.dto.MedOcrPrefillDto;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话内用药单识别：复用 OCR 服务，结果供人工确认后录入用药清单。
 */
@Component
@RequiredArgsConstructor
public class OcrMedSkill implements AgentSkill {

    private final MedOcrRecognitionService medOcrRecognitionService;

    @Override
    public AgentCapability capability() {
        return AgentCapability.OCR_MED;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.OCR_MED;
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        AgentChatCommand cmd = ctx.command();
        if (!StringUtils.hasText(cmd.imageBase64())) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_MED.name(),
                    "OCR_MED",
                    "请在对话框上传用药单/处方图片（JPG/PNG/WEBP，≤5MB）后识别；识别结果可在气泡内核对写入用药清单。",
                    List.of(AgentAction.openSheet("打开用药管理（手工录入）", "medications", cmd.peopleId())),
                    List.of(),
                    null);
        }
        try {
            byte[] bytes = decodeImage(cmd.imageBase64());
            String mime = StringUtils.hasText(cmd.imageMimeType()) ? cmd.imageMimeType() : "image/jpeg";
            MedOcrPrefillDto prefill = medOcrRecognitionService.recognize(bytes, mime);
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_MED.name(),
                    "OCR_MED",
                    buildReply(prefill),
                    List.of(),
                    List.of(),
                    prefill);
        } catch (BusinessException e) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_MED.name(),
                    "OCR_MED",
                    e.getMessage(),
                    List.of(AgentAction.openSheet("打开用药管理", "medications", cmd.peopleId())),
                    List.of(),
                    null);
        } catch (Exception e) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_MED.name(),
                    "OCR_MED",
                    "用药单识别失败，请检查图片清晰度或稍后重试。",
                    List.of(AgentAction.openSheet("打开用药管理", "medications", cmd.peopleId())),
                    List.of(),
                    null);
        }
    }

    public static boolean matchesMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        return containsAny(
                msg,
                "用药单",
                "处方",
                "药方",
                "药品清单",
                "带药",
                "medication",
                "prescription",
                "ocr-med");
    }

    private static String buildReply(MedOcrPrefillDto prefill) {
        int n = prefill.items() == null ? 0 : prefill.items().size();
        List<String> parts = new ArrayList<>();
        parts.add("用药单识别完成：已识别 " + n + " 种药品");
        if (prefill.warnings() != null && !prefill.warnings().isEmpty()) {
            parts.add("提示：" + String.join("；", prefill.warnings().subList(0, Math.min(3, prefill.warnings().size()))));
        }
        parts.add("请在对话中核对后确认写入用药清单（不会自动落库）。");
        return String.join("。", parts);
    }

    private static byte[] decodeImage(String imageBase64) {
        String raw = imageBase64.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        return Base64.getDecoder().decode(raw);
    }

    private static boolean containsAny(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
