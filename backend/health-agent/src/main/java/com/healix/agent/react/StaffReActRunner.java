package com.healix.agent.react;

import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.agent.support.AgentReplyPlainText;
import dev.langchain4j.service.Result;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 驾驶舱唯一执行入口：全部经 {@link StaffAgentTools}。
 * 软入口由 AiServices 选工具；无 Planner 时仅回落业务问答（不靠关键词猜能力）。
 * 硬芯片强制对应 Tool。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffReActRunner {

    private final StaffAgentTools staffAgentTools;

    @Autowired(required = false)
    private StaffCockpitAgent staffCockpitAgent;

    public boolean plannerReady() {
        return staffCockpitAgent != null;
    }

    /**
     * @param forced 芯片硬能力；null 表示软入口
     */
    public AgentResponse run(
            AgentChatCommand cmd,
            String sessionId,
            List<ChatTurn> history,
            String patientDisplayName,
            Consumer<AgentStreamEvent> sink,
            AgentCapability forced) {
        // 软入口带图：统一走自动识单工具
        if (forced == null
                && StringUtils.hasText(cmd.imageBase64())
                && StringUtils.hasText(cmd.peopleId())) {
            return invokeNamed(
                    cmd, sessionId, history, patientDisplayName, sink, "单据自动识别", staffAgentTools::ocrAutoClassify);
        }
        if (forced != null && forced != AgentCapability.GENERAL_CHAT) {
            return runForced(cmd, sessionId, history, patientDisplayName, sink, forced);
        }
        if (forced == AgentCapability.GENERAL_CHAT) {
            return invokeNamed(
                    cmd, sessionId, history, patientDisplayName, sink, "业务问答", staffAgentTools::answerGeneralChat);
        }
        // 软入口：有 Planner 则 ReAct；否则不猜意图，只走业务问答
        if (staffCockpitAgent != null) {
            return runPlanner(cmd, sessionId, history, patientDisplayName, sink);
        }
        return invokeNamed(
                cmd, sessionId, history, patientDisplayName, sink, "业务问答", staffAgentTools::answerGeneralChat);
    }

    private AgentResponse runForced(
            AgentChatCommand cmd,
            String sessionId,
            List<ChatTurn> history,
            String patientDisplayName,
            Consumer<AgentStreamEvent> sink,
            AgentCapability forced) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.skill(forced.label(), "强制工具"));
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("开始处理：" + forced.label()));
        return invokeNamed(
                cmd,
                sessionId,
                history,
                patientDisplayName,
                sink,
                forced.label(),
                () -> switch (forced) {
                    case CREATE_ARCHIVE -> staffAgentTools.createArchiveDraft();
                    case CHAT_EXTRACT -> staffAgentTools.extractChatRecords();
                    case CARE_PLAN -> staffAgentTools.carePlanFromChip();
                    case REPORT_SUMMARY -> staffAgentTools.reportSummaryFromChip();
                    case OCR_LAB -> staffAgentTools.ocrLab();
                    case OCR_EXAM -> staffAgentTools.ocrExam();
                    case OCR_MED -> staffAgentTools.ocrMed();
                    default -> "ERROR: 不支持的能力 " + forced.name();
                });
    }

    private AgentResponse invokeNamed(
            AgentChatCommand cmd,
            String sessionId,
            List<ChatTurn> history,
            String patientDisplayName,
            Consumer<AgentStreamEvent> sink,
            String label,
            java.util.concurrent.Callable<String> toolCall) {
        StaffAgentTurnContext.open(cmd, sessionId, history, patientDisplayName, sink);
        try {
            String toolResult = toolCall.call();
            AgentResponse structured = StaffAgentTurnContext.require().consumeStructured();
            if (structured != null) {
                return withSession(sessionId, structured);
            }
            String reply = StringUtils.hasText(toolResult) && toolResult.startsWith("ERROR:")
                    ? toolResult.substring("ERROR:".length()).trim()
                    : (StringUtils.hasText(toolResult) ? toolResult : "未生成结果，请重试。");
            return new AgentResponse(
                    sessionId,
                    AgentCapability.GENERAL_CHAT.name(),
                    "GENERAL_CHAT",
                    reply,
                    List.of(),
                    List.of(),
                    null);
        } catch (Exception e) {
            log.warn("[react] tool {} failed: {}", label, e.getMessage(), e);
            return new AgentResponse(
                    sessionId,
                    AgentCapability.GENERAL_CHAT.name(),
                    "GENERAL_CHAT",
                    "处理失败：" + (e.getMessage() == null ? "请重试" : e.getMessage()),
                    List.of(),
                    List.of(),
                    null);
        } finally {
            StaffAgentTurnContext.close();
        }
    }

    private AgentResponse runPlanner(
            AgentChatCommand cmd,
            String sessionId,
            List<ChatTurn> history,
            String patientDisplayName,
            Consumer<AgentStreamEvent> sink) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.skill("智能体", "ReAct 编排"));
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在思考并选择工具…"));
        StaffAgentTurnContext.open(cmd, sessionId, history, patientDisplayName, sink);
        try {
            Result<String> result = staffCockpitAgent.handle(buildEnvelope(cmd, patientDisplayName, history));
            AgentResponse structured = StaffAgentTurnContext.require().consumeStructured();
            if (structured != null) {
                return withSession(sessionId, structured);
            }
            String content = result == null ? null : result.content();
            String reply = StringUtils.hasText(content)
                    ? AgentReplyPlainText.sanitize(content)
                    : "我先记下了。可以继续说明，或点上方能力芯片。";
            return new AgentResponse(
                    sessionId,
                    AgentCapability.GENERAL_CHAT.name(),
                    "GENERAL_CHAT",
                    reply,
                    List.of(),
                    List.of(),
                    null);
        } catch (Exception e) {
            log.warn("[react] planner failed: {}", e.getMessage(), e);
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("编排失败，回落业务问答"));
            StaffAgentTurnContext.close();
            return invokeNamed(
                    cmd, sessionId, history, patientDisplayName, sink, "业务问答", staffAgentTools::answerGeneralChat);
        } finally {
            if (StaffAgentTurnContext.current() != null) {
                StaffAgentTurnContext.close();
            }
        }
    }

    private static AgentResponse withSession(String sessionId, AgentResponse response) {
        if (response == null) {
            return null;
        }
        if (sessionId != null && sessionId.equals(response.sessionId())) {
            return response;
        }
        return new AgentResponse(
                sessionId,
                response.capability(),
                response.intent(),
                response.reply(),
                response.actions(),
                response.safetyFlags(),
                response.extracted());
    }

    static String buildEnvelope(AgentChatCommand cmd, String patientDisplayName, List<ChatTurn> history) {
        boolean hasPatient = StringUtils.hasText(cmd.peopleId());
        StringBuilder sb = new StringBuilder();
        sb.append("hasPatient=").append(hasPatient ? "yes" : "no").append('\n');
        if (hasPatient) {
            sb.append("peopleId=").append(cmd.peopleId()).append('\n');
            sb.append("patientName=")
                    .append(StringUtils.hasText(patientDisplayName) ? patientDisplayName : "患者")
                    .append('\n');
        }
        sb.append("hasImage=").append(StringUtils.hasText(cmd.imageBase64()) ? "yes" : "no").append('\n');
        if (history != null && !history.isEmpty()) {
            sb.append("recentHistory:\n");
            int from = Math.max(0, history.size() - 4);
            for (int i = from; i < history.size(); i++) {
                ChatTurn turn = history.get(i);
                if (StringUtils.hasText(turn.user())) {
                    sb.append("- user: ").append(trim(turn.user(), 200)).append('\n');
                }
                if (StringUtils.hasText(turn.assistant())) {
                    sb.append("- assistant: ").append(trim(turn.assistant(), 200)).append('\n');
                }
            }
        }
        sb.append("---\n");
        sb.append(cmd.message() == null ? "" : cmd.message().trim());
        return sb.toString();
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
