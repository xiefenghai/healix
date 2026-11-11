package com.healix.agent.handler;

import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.ocr.LabOcrRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.core.observation.dto.LabOcrPrefillDto;
import com.healix.core.storage.ImageFileService;
import com.healix.core.storage.ObjectKeys;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 对话内检验单识别（由 StaffAgentTools 调用）。 */
@Component
@RequiredArgsConstructor
public class OcrLabHandler {

    private final LabOcrRecognitionService labOcrRecognitionService;
    private final ImageFileService imageFileService;

    public AgentResponse execute(AgentHandlerContext ctx) {
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
            byte[] bytes = OcrHandlerSupport.decodeImage(cmd.imageBase64());
            String mime = OcrHandlerSupport.mimeOrJpeg(cmd.imageMimeType());
            LabOcrPrefillDto prefill = labOcrRecognitionService.recognize(bytes, mime);
            if (StringUtils.hasText(cmd.tenantId()) && StringUtils.hasText(cmd.peopleId())) {
                prefill = prefill.withImageKey(
                        imageFileService.save(cmd.tenantId(), cmd.peopleId(), ObjectKeys.LAB, bytes, mime));
            }
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

    private static String buildReply(LabOcrPrefillDto prefill) {
        int mapped = prefill.items() == null ? 0 : prefill.items().size();
        int ignored = prefill.ignoredItems() == null ? 0 : prefill.ignoredItems().size();
        int total = mapped + ignored;
        List<String> parts = new ArrayList<>();
        parts.add("检验单识别完成：图片共检出 " + total + " 项，成功识别 " + mapped + " 项"
                + (ignored > 0 ? "，未收录忽略 " + ignored + " 项" : ""));
        String hint = OcrHandlerSupport.warningsHint(prefill.warnings());
        if (hint != null) {
            parts.add(hint);
        }
        parts.add("请在对话中核对后确认入库（不会自动落库）。");
        return String.join("。", parts);
    }
}
