package com.healix.agent.react;

import com.healix.agent.careplan.CarePlanAgentService;
import com.healix.agent.careplan.CarePlanStreamSummary;
import com.healix.agent.extract.ChatExtractCard;
import com.healix.agent.extract.ChatExtractService;
import com.healix.agent.extract.ChatScreenshotDetector;
import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.agent.ocr.ObservationReportIngestService;
import com.healix.agent.handler.ChatExtractHandler;
import com.healix.agent.handler.CreateArchiveHandler;
import com.healix.agent.handler.GeneralChatHandler;
import com.healix.agent.handler.OcrExamHandler;
import com.healix.agent.handler.OcrLabHandler;
import com.healix.agent.handler.OcrMedHandler;
import com.healix.agent.handler.ReportSummaryHandler;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.agent.tool.StaffBizTools;
import com.healix.common.exception.BusinessException;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.observation.dto.ObservationOcrPreviewDto;
import dev.langchain4j.agent.tool.Tool;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 驾驶舱 ReAct 工具门面：委托 {@code handler.*} 能力实现 / 领域服务，只产出结构化结果（卡片），不静默写库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaffAgentTools {

    private final CreateArchiveHandler createArchiveHandler;
    private final ChatExtractHandler chatExtractHandler;
    private final CarePlanAgentService carePlanAgentService;
    private final ReportSummaryHandler reportSummaryHandler;
    private final OcrLabHandler ocrLabHandler;
    private final OcrExamHandler ocrExamHandler;
    private final OcrMedHandler ocrMedHandler;
    private final GeneralChatHandler generalChatHandler;
    private final ObservationReportIngestService observationReportIngestService;
    private final ChatExtractService chatExtractService;
    private final StaffBizTools staffBizTools;

    @Tool("新建患者档案。用户粘贴含姓名+手机号/年龄或生日/性别的建档稿（如「18758222123 李默 35岁 男」），或明确要登记/建档新患者时调用；系统会抽取并预填右侧表单。用户说「建组」「创建健管组」「新建组」是建健管组不是建档，禁止调用本工具。不要把已选患者的补血压/体重当成建档。")
    public String createArchiveDraft() {
        return runPrimary("患者建档", () -> createArchiveHandler.execute(StaffAgentTurnContext.require().handlerContext()));
    }

    @Tool("从面诊或聊天长文提取指标/病史/用药/过敏等，生成核对卡。必须已选中患者；机构会话不要调用。")
    public String extractChatRecords() {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        if (!StringUtils.hasText(turn.command().peopleId())) {
            return "ERROR: 未选患者，不能整理记录。若是新患者建档请改用 createArchiveDraft。";
        }
        return runPrimary("整理这段记录", () -> chatExtractHandler.execute(turn.handlerContext()));
    }

    @Tool("整包生成健康管理方案（新建或重新生成）。必须已选中患者。用户说生成/制定/重新生成方案时调用。")
    public String generateCarePlan() {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        if (!StringUtils.hasText(turn.command().peopleId())) {
            return "ERROR: 未选患者，不能生成方案。";
        }
        return runPrimary("制定管理方案", () -> streamCarePlan(turn, false));
    }

    @Tool("在现有方案草稿上修订（改运动/饮食/任务等）。无草稿时会自动改为整包生成。必须已选中患者。")
    public String reviseCarePlan() {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        if (!StringUtils.hasText(turn.command().peopleId())) {
            return "ERROR: 未选患者，不能修订方案。";
        }
        return runPrimary("修订管理方案", () -> streamCarePlan(turn, true));
    }

    /**
     * 芯片硬入口：优先修订（有草稿则改，无草稿则服务内回退生成），避免再靠关键词猜意图。
     */
    public String carePlanFromChip() {
        return reviseCarePlan();
    }

    @Tool("新生成管理报告点评寄语。必须已选中患者。")
    public String generateReportSummary() {
        return runReportSummary(ReportSummaryHandler.ReviseMode.FORCE_GENERATE, "管理报告点评");
    }

    @Tool("修订已有报告点评/寄语。必须已选中患者且已有点评草稿。")
    public String reviseReportSummary() {
        return runReportSummary(ReportSummaryHandler.ReviseMode.FORCE_REVISE, "修订报告点评");
    }

    /** 芯片硬入口：有点评则修订，否则新生成（不靠关键词）。 */
    public String reportSummaryFromChip() {
        return runReportSummary(ReportSummaryHandler.ReviseMode.AUTO, "管理报告点评");
    }

    private String runReportSummary(ReportSummaryHandler.ReviseMode mode, String label) {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        if (!StringUtils.hasText(turn.command().peopleId())) {
            return "ERROR: 未选患者，不能写报告点评。";
        }
        Consumer<AgentStreamEvent> sink = turn.sink();
        return runPrimary(
                label,
                () -> reportSummaryHandler.executeStream(turn.handlerContext(), sink, mode));
    }

    @Tool("识别当前消息附带的检验单图片。需要已选患者且消息带图。")
    public String ocrLab() {
        return runOcr(AgentCapability.OCR_LAB, ocrLabHandler::execute);
    }

    @Tool("识别当前消息附带的检查单图片。需要已选患者且消息带图。")
    public String ocrExam() {
        return runOcr(AgentCapability.OCR_EXAM, ocrExamHandler::execute);
    }

    @Tool("识别当前消息附带的用药单图片。需要已选患者且消息带图。")
    public String ocrMed() {
        return runOcr(AgentCapability.OCR_MED, ocrMedHandler::execute);
    }

    @Tool("自动识别上传图片：聊天截图则整理记录，否则按检验/检查/用药单分类。需要已选患者且消息带图。")
    public String ocrAutoClassify() {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        AgentChatCommand cmd = turn.command();
        if (!StringUtils.hasText(cmd.peopleId())) {
            return "ERROR: 未选患者，不能识别单据。";
        }
        if (!StringUtils.hasText(cmd.imageBase64())) {
            return "ERROR: 当前消息没有图片。";
        }
        return runPrimary("单据自动识别", () -> executeOcrAuto(turn));
    }

    @Tool("业务问答：解释操作、引导建组/分组、回答驾驶舱问题。无明确建档/整理/方案意图时使用。")
    public String answerGeneralChat() {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        Consumer<AgentStreamEvent> sink = turn.sink();
        return runPrimary(
                "业务问答",
                () -> sink != null
                        ? generalChatHandler.executeStream(turn.handlerContext(), sink)
                        : generalChatHandler.execute(turn.handlerContext()));
    }

    @Tool("加载当前焦点患者的只读业务快照（任务、方案状态等），用于回答问题。已选患者时可用。")
    public String patientContext() {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        AgentChatCommand cmd = turn.command();
        if (!StringUtils.hasText(cmd.peopleId())) {
            return "当前是机构会话，没有焦点患者。";
        }
        emitProgress(turn, "加载患者上下文");
        try {
            var bundle = staffBizTools.loadFocusContext(cmd.tenantId(), cmd.orgId(), cmd.peopleId());
            return bundle == null || !StringUtils.hasText(bundle.text())
                    ? "未取到患者上下文"
                    : bundle.text();
        } catch (Exception e) {
            log.warn("patientContext failed: {}", e.getMessage());
            return "加载患者上下文失败：" + e.getMessage();
        }
    }

    @Tool("加载机构今日待办与优先名单摘要，用于机构会话问答。")
    public String orgBrief() {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        AgentChatCommand cmd = turn.command();
        emitProgress(turn, "加载机构今日");
        try {
            var bundle = staffBizTools.loadOrgBrief(cmd.tenantId(), cmd.orgId(), cmd.staffId(), cmd.message());
            return bundle == null || !StringUtils.hasText(bundle.text())
                    ? "未取到机构摘要"
                    : bundle.text();
        } catch (Exception e) {
            log.warn("orgBrief failed: {}", e.getMessage());
            return "加载机构摘要失败：" + e.getMessage();
        }
    }

    private String runOcr(AgentCapability cap, java.util.function.Function<
                    com.healix.agent.handler.AgentHandlerContext, AgentResponse> exec) {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        AgentChatCommand cmd = turn.command();
        if (!StringUtils.hasText(cmd.peopleId())) {
            return "ERROR: 未选患者，不能识别单据。";
        }
        if (!StringUtils.hasText(cmd.imageBase64())) {
            return "ERROR: 当前消息没有图片。请让用户上传后再识别。";
        }
        return runPrimary(cap.label(), () -> exec.apply(turn.handlerContext()));
    }

    private String runPrimary(String label, java.util.concurrent.Callable<AgentResponse> call) {
        StaffAgentTurnContext turn = StaffAgentTurnContext.require();
        emitProgress(turn, label);
        long started = System.currentTimeMillis();
        try {
            AgentResponse response = call.call();
            turn.record(response);
            if (turn.sink() != null) {
                AgentStreamEvent.safeEmit(
                        turn.sink(),
                        AgentStreamEvent.tool(
                                response.capability() == null ? label : response.capability(),
                                "done",
                                label + " · " + (System.currentTimeMillis() - started) + "ms"));
            }
            return summarize(response);
        } catch (Exception e) {
            log.warn("tool {} failed: {}", label, e.getMessage());
            return "ERROR: " + label + " 失败：" + e.getMessage();
        }
    }

    private AgentResponse executeOcrAuto(StaffAgentTurnContext turn) {
        AgentChatCommand cmd = turn.command();
        Consumer<AgentStreamEvent> sink = turn.sink();
        long started = System.currentTimeMillis();
        try {
            byte[] bytes = decodeImageBase64(cmd.imageBase64());
            String mime = StringUtils.hasText(cmd.imageMimeType()) ? cmd.imageMimeType() : "image/jpeg";
            String text = observationReportIngestService.readImageText(
                    cmd.tenantId(), cmd.orgId(), cmd.peopleId(), bytes, mime);
            if (ChatScreenshotDetector.isChatRecord(text)) {
                if (sink != null) {
                    AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool("OCR_AUTO", "done", "识别为聊天记录"));
                    AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在整理这段记录…"));
                }
                ChatExtractCard card = chatExtractService.extract(cmd.tenantId(), cmd.peopleId(), text);
                card.setSourceText(text.trim());
                int count = card.getItems() == null ? 0 : card.getItems().size();
                String preview = count > 0
                        ? "提取到 " + count + " 项，请在卡片里核对"
                        : "这段话里没有对上可写入的内容";
                return new AgentResponse(
                        turn.sessionId(),
                        AgentCapability.CHAT_EXTRACT.name(),
                        "CHAT_EXTRACT",
                        preview,
                        List.of(),
                        List.of(),
                        card);
            }
            ObservationOcrPreviewDto preview =
                    observationReportIngestService.structureFromText(
                            cmd.tenantId(), cmd.peopleId(), bytes, mime, text);
            String kind = preview.kind() == null ? "EXAM" : preview.kind().trim().toUpperCase();
            AgentCapability cap =
                    switch (kind) {
                        case "LAB" -> AgentCapability.OCR_LAB;
                        case "MED" -> AgentCapability.OCR_MED;
                        default -> AgentCapability.OCR_EXAM;
                    };
            String kindLabel =
                    switch (kind) {
                        case "LAB" -> "检验单";
                        case "MED" -> "用药单";
                        default -> "检查单";
                    };
            if (sink != null) {
                AgentStreamEvent.safeEmit(
                        sink,
                        AgentStreamEvent.tool(
                                "OCR_AUTO",
                                "done",
                                "识别为" + kindLabel + " · " + (System.currentTimeMillis() - started) + "ms"));
            }
            String title = StringUtils.hasText(preview.title()) ? preview.title() : kindLabel;
            return new AgentResponse(
                    turn.sessionId(),
                    cap.name(),
                    cap.name(),
                    "已识别为「" + title + "」，请在下方卡片核对后确认入库。",
                    List.of(),
                    List.of(),
                    preview);
        } catch (BusinessException e) {
            return new AgentResponse(
                    turn.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_AUTO",
                    e.getMessage(),
                    List.of(),
                    List.of(),
                    null);
        } catch (Exception e) {
            return new AgentResponse(
                    turn.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_AUTO",
                    "识别失败，请检查图片清晰度或稍后重试。",
                    List.of(),
                    List.of(),
                    null);
        }
    }

    private static byte[] decodeImageBase64(String imageBase64) {
        String raw = imageBase64.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        return Base64.getDecoder().decode(raw);
    }

    private AgentResponse streamCarePlan(StaffAgentTurnContext turn, boolean revise) {
        AgentChatCommand cmd = turn.command();
        String instruction = StringUtils.hasText(cmd.message()) ? cmd.message() : "生成管理方案";
        Consumer<AgentStreamEvent> sink = turn.sink();
        List<ChatTurn> history = turn.handlerContext().history();
        CarePlanBundleDto bundle;
        if (revise) {
            bundle = carePlanAgentService.reviseStream(
                    cmd.tenantId(),
                    cmd.orgId(),
                    cmd.peopleId(),
                    cmd.staffId(),
                    instruction,
                    sink,
                    history);
        } else {
            bundle = carePlanAgentService.generateStream(
                    cmd.tenantId(),
                    cmd.orgId(),
                    cmd.peopleId(),
                    cmd.staffId(),
                    instruction,
                    null,
                    null,
                    true,
                    sink,
                    true,
                    history);
        }
        return new AgentResponse(
                turn.sessionId(),
                AgentCapability.CARE_PLAN.name(),
                revise ? "CARE_PLAN_REVISE" : "CARE_PLAN_GENERATE",
                CarePlanStreamSummary.buildReply(bundle, revise),
                List.of(
                        AgentAction.openSheet("编辑方案草稿", "care-plan", cmd.peopleId()),
                        AgentAction.publishCarePlan("确认发布方案", cmd.peopleId()),
                        AgentAction.triggerCapability("继续闲聊", "GENERAL_CHAT", cmd.peopleId())),
                List.of(),
                bundle);
    }

    private static void emitProgress(StaffAgentTurnContext turn, String label) {
        if (turn.sink() != null) {
            AgentStreamEvent.safeEmit(turn.sink(), AgentStreamEvent.skill(label, "工具：" + label));
            AgentStreamEvent.safeEmit(turn.sink(), AgentStreamEvent.progress("开始处理：" + label));
            AgentStreamEvent.safeEmit(turn.sink(), AgentStreamEvent.tool(label, "running", label));
        }
    }

    private static String summarize(AgentResponse response) {
        if (response == null) {
            return "EMPTY";
        }
        String reply = response.reply() == null ? "" : response.reply();
        return "capability="
                + response.capability()
                + " intent="
                + response.intent()
                + " reply="
                + (reply.length() > 500 ? reply.substring(0, 500) + "…" : reply);
    }
}
