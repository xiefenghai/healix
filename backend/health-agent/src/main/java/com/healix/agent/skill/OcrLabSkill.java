package com.healix.agent.skill;

import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.ocr.LabOcrRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.core.observation.dto.LabOcrPrefillDto;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话内检验单识别：复用观测页 OCR 服务，结果供人工确认后录入。
 */
@Component
@RequiredArgsConstructor
public class OcrLabSkill implements AgentSkill {

    private final LabOcrRecognitionService labOcrRecognitionService;

    @Override
    public AgentCapability capability() {
        return AgentCapability.OCR_LAB;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.OCR_LAB;
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        AgentChatCommand cmd = ctx.command();
        String path = "/workspace/patients/" + cmd.peopleId() + "/observations/labs?ocr=1";
        if (!StringUtils.hasText(cmd.imageBase64())) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_LAB",
                    "请上传检验单图片（JPG/PNG/WEBP，≤5MB）后识别；也可前往检验录入页使用「拍照识别」。",
                    List.of(AgentAction.navigate("前往检验录入", path)),
                    List.of(),
                    null);
        }
        try {
            byte[] bytes = decodeImage(cmd.imageBase64());
            String mime = StringUtils.hasText(cmd.imageMimeType()) ? cmd.imageMimeType() : "image/jpeg";
            LabOcrPrefillDto prefill = labOcrRecognitionService.recognize(bytes, mime);
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_LAB",
                    buildReply(prefill),
                    List.of(),
                    List.of(),
                    prefill);
        } catch (BusinessException e) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_LAB",
                    e.getMessage(),
                    List.of(),
                    List.of(),
                    null);
        } catch (Exception e) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_LAB",
                    "检验单识别失败，请检查图片清晰度或稍后重试。",
                    List.of(),
                    List.of(),
                    null);
        }
    }

    public static boolean matchesMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        return containsAny(msg, "检验单", "化验单", "检验报告", "化验报告", "lab report", "ocr-lab");
    }

    private static String buildReply(LabOcrPrefillDto prefill) {
        int mapped = prefill.items() == null ? 0 : prefill.items().size();
        int ignored = prefill.ignoredItems() == null ? 0 : prefill.ignoredItems().size();
        List<String> parts = new ArrayList<>();
        parts.add("检验单识别完成：已映射 " + mapped + " 项");
        if (ignored > 0) {
            parts.add("忽略未收录 " + ignored + " 项");
        }
        if (prefill.warnings() != null && !prefill.warnings().isEmpty()) {
            parts.add("提示：" + String.join("；", prefill.warnings().subList(0, Math.min(3, prefill.warnings().size()))));
        }
        parts.add("请在对话中核对后确认入库（不会自动落库）。");
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
