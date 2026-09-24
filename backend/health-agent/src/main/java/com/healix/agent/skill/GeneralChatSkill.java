package com.healix.agent.skill;

import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.agent.support.AgentReplyPlainText;
import com.healix.agent.tool.StaffBizTools;
import com.healix.agent.support.AnswerTokenStreamer;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.cockpit.dto.CockpitFocusDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通用闲聊 / 驾驶舱对话：有 peopleId 时带档案+焦点上下文；无 peopleId 为机构级会话。
 */
@Component
@RequiredArgsConstructor
public class GeneralChatSkill implements AgentSkill {

    private final LlmClient llmClient;
    private final CarePlanContextService contextService;
    private final StaffBizTools staffBizTools;

    @Override
    public AgentCapability capability() {
        return AgentCapability.GENERAL_CHAT;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.GENERAL_CHAT;
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        return executeStream(ctx, null);
    }

    public AgentResponse executeStream(AgentSkillContext ctx, Consumer<AgentStreamEvent> sink) {
        AgentChatCommand cmd = ctx.command();
        boolean orgSession = !StringUtils.hasText(cmd.peopleId());
        String systemPrompt;
        CockpitFocusDto focus = null;
        StaffBizTools.OrgBriefBundle orgBrief = null;
        List<AgentAction> actions = new ArrayList<>();

        if (orgSession) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("准备机构级上下文…"));
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("loadOrgBrief", "running", "读取今日优先与待办摘要"));
            orgBrief = staffBizTools.loadOrgBrief(cmd.tenantId(), cmd.orgId(), cmd.staffId(), cmd.message());
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.tool(
                            "loadOrgBrief",
                            "done",
                            StringUtils.hasText(orgBrief.text()) ? "已加载机构摘要" : "摘要为空"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingStart("整理机构级会话目标…"));
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.thinkingDelta("依据今日优先名单回答，禁止编造名单外患者。\n"));
            systemPrompt = buildOrgSystemPrompt(orgBrief.text());
            if (StaffBizTools.wantsCompleteTask(cmd.message())) {
                systemPrompt +=
                        """

                        用户想办结今日待办：机构会话不能直接关单。请依据优先名单，引导健管师先点击下方「处理某某的待办」注入患者，
                        再在患者会话里用「确认办结」打开与工作台一致的填单弹窗（填完提交才写库）。
                        """;
            }
            actions.addAll(orgBrief.actions());
        } else {
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("loadFocusSnapshot", "running", "读取评估/完整度/依从/任务"));
            StaffBizTools.FocusBundle focusBundle =
                    staffBizTools.loadFocusContext(cmd.tenantId(), cmd.orgId(), cmd.peopleId());
            focus = focusBundle.focus();
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.tool(
                            "loadFocusSnapshot",
                            "done",
                            StringUtils.hasText(focusBundle.text()) ? "已加载焦点快照" : "焦点为空"));

            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("loadPatientContext", "running", "读取档案与观测摘要"));
            CarePlanContext patientCtx = contextService.load(cmd.tenantId(), cmd.peopleId());
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("loadPatientContext", "done", "已加载患者上下文"));
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.thinkingStart(
                            "结合患者「" + ctx.patientDisplayName() + "」档案与焦点理解问题…"));
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.thinkingDelta(
                            "用户问题：" + truncate(cmd.message(), 120) + "\n"));
            systemPrompt = buildSystemPrompt(ctx.patientDisplayName(), patientCtx, focusBundle.text());
            if (StaffBizTools.wantsCompleteTask(cmd.message())) {
                systemPrompt +=
                        """

                        用户要办理当前患者的工作台待办。请严格依据焦点快照中的开放任务：
                        1）按超期优先、再按紧急度给出简短办理建议与顺序；
                        2）说明每条建议怎么处理（填单办结 / 打开方案页 / 审阅报告等）；
                        3）不要声称已办结或已写库——须健管师点击下方「确认办结」等按钮并人工确认后才生效。
                        """;
            }
            actions.addAll(staffBizTools.suggestPatientActions(cmd.peopleId(), cmd.message(), focus));
        }

        String reply;
        if (sink != null && llmClient.isEnabled()) {
            long thinkStarted = System.currentTimeMillis();
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.thinkingDelta("开始推理：梳理要点并组织对健管师的回复。\n"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool("llm.chat", "running", "调用大模型生成回复"));
            // 原始 token 原样下发；前端 visibleAnswerFromRawStream 剥离【思考】，边收边显示【回答】
            StringBuilder rawBuf = new StringBuilder();
            final boolean[] answerStarted = {false};
            var llm = llmClient.streamChat(
                    orgSession ? "COCKPIT_ORG_CHAT" : "GENERAL_CHAT",
                    systemPrompt,
                    cmd.message(),
                    ctx.history(),
                    token -> {
                        if (token == null || token.isEmpty()) {
                            return;
                        }
                        rawBuf.append(token);
                        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(token));
                        if (!answerStarted[0]) {
                            String s = rawBuf.toString();
                            boolean hitAnswer = s.contains("【回答】") || s.contains("[回答]");
                            boolean plain =
                                    s.length() >= 16
                                            && !s.contains("【思考】")
                                            && !s.contains("[思考]")
                                            && !s.trim().startsWith("{");
                            if (hitAnswer || plain) {
                                answerStarted[0] = true;
                                long ms = System.currentTimeMillis() - thinkStarted;
                                AgentStreamEvent.safeEmit(
                                        sink,
                                        AgentStreamEvent.tool(
                                                "llm.chat", "done", "开始输出回复 · " + ms + "ms"));
                                AgentStreamEvent.safeEmit(
                                        sink, AgentStreamEvent.thinkingDone("开始组织正式回复", ms));
                            }
                        }
                    });
            long thinkMs = System.currentTimeMillis() - thinkStarted;
            String raw = llm.fromLlm() && StringUtils.hasText(llm.content())
                    ? llm.content()
                    : rawBuf.toString();
            ThinkAnswer split = splitThinkAnswer(raw);
            if (StringUtils.hasText(split.thinking()) && !answerStarted[0]) {
                AgentStreamEvent.safeEmit(
                        sink, AgentStreamEvent.thinkingDelta("\n—— 模型思考 ——\n" + split.thinking().trim() + "\n"));
            }
            reply = split.answer();
            if (StringUtils.hasText(reply)) {
                if (!answerStarted[0]) {
                    AgentStreamEvent.safeEmit(
                            sink,
                            AgentStreamEvent.tool(
                                    "llm.chat",
                                    "done",
                                    "生成完成 · " + reply.length() + " 字 · " + thinkMs + "ms"));
                    AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingDone(null, thinkMs));
                    new AnswerTokenStreamer(sink).finish(AgentReplyPlainText.sanitize(reply));
                }
            } else {
                AgentStreamEvent.safeEmit(
                        sink, AgentStreamEvent.tool("llm.chat", "done", "未返回有效内容 · " + thinkMs + "ms"));
                AgentStreamEvent.safeEmit(
                        sink, AgentStreamEvent.thinkingDone("模型未返回有效内容", thinkMs));
            }
        } else {
            var llm = llmClient.chat(
                    orgSession ? "COCKPIT_ORG_CHAT" : "GENERAL_CHAT",
                    systemPrompt,
                    cmd.message(),
                    ctx.history());
            String raw = llm.fromLlm() && StringUtils.hasText(llm.content()) ? llm.content() : null;
            reply = splitThinkAnswer(raw).answer();
            if (!StringUtils.hasText(reply)) {
                reply = fallbackReply(orgSession);
            }
        }

        if (!StringUtils.hasText(reply)) {
            reply = fallbackReply(orgSession);
        } else {
            reply = AgentReplyPlainText.sanitize(reply);
            if (!StringUtils.hasText(reply)) {
                reply = fallbackReply(orgSession);
            }
        }

        if (!orgSession) {
            appendDraftActions(cmd, focus, actions, sink);
        }

        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.GENERAL_CHAT.name(),
                orgSession ? "ORG_CHAT" : "GENERAL_CHAT",
                reply,
                dedupeActions(actions),
                List.of(),
                null);
    }

    private void appendDraftActions(
            AgentChatCommand cmd,
            CockpitFocusDto focus,
            List<AgentAction> actions,
            Consumer<AgentStreamEvent> sink) {
        boolean followupDraft = StaffBizTools.wantsFollowupDraft(cmd.message());
        boolean chatDraft = StaffBizTools.wantsChatDraft(cmd.message());
        if (!followupDraft && !chatDraft) {
            return;
        }
        String kind = followupDraft ? "随访" : "沟通";
        AgentStreamEvent.safeEmit(
                sink, AgentStreamEvent.tool("draft." + kind, "running", "生成" + kind + "草稿文案"));
        String draft = generateDraft(cmd, focus, followupDraft);
        AgentStreamEvent.safeEmit(
                sink,
                AgentStreamEvent.tool(
                        "draft." + kind,
                        "done",
                        StringUtils.hasText(draft) ? "草稿已就绪" : "草稿生成失败"));
        if (!StringUtils.hasText(draft)) {
            return;
        }
        Map<String, Object> payload = AgentAction.draftPayload(draft, followupDraft);
        if (followupDraft) {
            replaceOrAdd(
                    actions,
                    AgentAction.createFollowupTask("一键创建随访待办", cmd.peopleId(), "ROUTINE", draft));
            replaceOrAdd(
                    actions,
                    AgentAction.openSheet("打开随访页完善", "followups", cmd.peopleId(), payload));
        } else {
            if (focus != null && Boolean.TRUE.equals(focus.getClientLinked())) {
                replaceOrAdd(
                        actions,
                        AgentAction.sendCareChat("一键发送给患者", cmd.peopleId(), draft));
                replaceOrAdd(actions, AgentAction.nudgePatient("站内提醒打卡/用药", cmd.peopleId()));
                replaceOrAdd(
                        actions,
                        AgentAction.openSheet("打开沟通窗口", "care-chat", cmd.peopleId(), payload));
            } else {
                // 未绑 C：无法送达，引导改为随访待办或仅本地预填
                replaceOrAdd(
                        actions,
                        AgentAction.createFollowupTask("改为随访待办", cmd.peopleId(), "ROUTINE", draft));
                replaceOrAdd(
                        actions,
                        AgentAction.openSheet("本地预填沟通草稿", "care-chat", cmd.peopleId(), payload));
            }
        }
    }

    private String generateDraft(AgentChatCommand cmd, CockpitFocusDto focus, boolean followup) {
        if (!llmClient.isEnabled()) {
            return heuristicDraft(focus, followup);
        }
        String system =
                followup
                        ? """
                        你是健管师随访助手。根据患者摘要，用纯中文写一段可直接粘贴到随访「指导建议」的草稿（120～220字）。
                        不要 Markdown，不要标题，不要诊断或处方，只写随访关注点与沟通建议。
                        """
                        : """
                        你是健管师沟通助手。根据患者摘要，写一段发给患者的沟通草稿（80～160字，语气专业温和）。
                        不要 Markdown，不要诊断或处方，提醒遵医嘱与按时打卡即可。
                        """;
        String user = "患者摘要：\n"
                + (focus == null ? cmd.peopleId() : JsonUtils.toJson(briefFocus(focus)))
                + "\n健管师诉求："
                + cmd.message();
        var llm = llmClient.chat(followup ? "STAFF_FOLLOWUP_DRAFT" : "STAFF_CHAT_DRAFT", system, user, List.of());
        if (llm.fromLlm() && StringUtils.hasText(llm.content())) {
            return AgentReplyPlainText.sanitize(llm.content());
        }
        return heuristicDraft(focus, followup);
    }

    private static Map<String, Object> briefFocus(CockpitFocusDto focus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", focus.getDisplayName());
        m.put("archiveCompletenessPercent", focus.getArchiveCompletenessPercent());
        m.put("planRate7d", focus.getPlanRate7d());
        m.put("bloodPressure", focus.getBloodPressure());
        if (focus.getAssessmentTags() != null) {
            m.put(
                    "assessmentTags",
                    focus.getAssessmentTags().stream()
                            .map(CockpitFocusDto.AssessmentTag::getText)
                            .toList());
        }
        if (focus.getDiseaseLabels() != null) {
            m.put("diseaseLabels", focus.getDiseaseLabels());
        }
        return m;
    }

    private static String heuristicDraft(CockpitFocusDto focus, boolean followup) {
        String name = focus != null && StringUtils.hasText(focus.getDisplayName()) ? focus.getDisplayName() : "您";
        if (followup) {
            StringBuilder sb = new StringBuilder();
            sb.append("随访关注：了解近期症状、用药与血压/血糖自测情况。");
            if (focus != null && focus.getPlanRate7d() != null && focus.getPlanRate7d() < 0.7) {
                sb.append("近7日方案依从偏低，需核实打卡障碍并鼓励完成今日任务。");
            }
            if (focus != null
                    && focus.getArchiveCompletenessPercent() != null
                    && focus.getArchiveCompletenessPercent() < 80) {
                sb.append("档案尚有缺项，随访时可一并补采关键信息。");
            }
            sb.append("结束时确认下次随访时间，并提醒不适及时就医。");
            return sb.toString();
        }
        return name
                + "您好，我是您的健康管理师。想跟进一下近期血压/血糖与方案执行情况，如有不适请及时告知；请尽量按计划完成打卡，有问题随时联系我。";
    }

    private static void replaceOrAdd(List<AgentAction> actions, AgentAction neu) {
        actions.removeIf(
                a -> "OPEN_SHEET".equals(a.type())
                        && neu.path() != null
                        && neu.path().equals(a.path()));
        actions.add(0, neu);
        if (actions.size() > 4) {
            actions.subList(4, actions.size()).clear();
        }
    }

    private static List<AgentAction> dedupeActions(List<AgentAction> actions) {
        List<AgentAction> out = new ArrayList<>();
        for (AgentAction a : actions) {
            boolean dup = out.stream()
                    .anyMatch(x -> x.type().equals(a.type())
                            && eq(x.path(), a.path())
                            && eq(x.peopleId(), a.peopleId())
                            && eq(x.label(), a.label())
                            // COMPLETE_TASK 等同 label 时仍按 taskId 区分（两条定期随访）
                            && eq(actionIdentity(x), actionIdentity(a)));
            if (!dup) {
                out.add(a);
            }
        }
        return out;
    }

    /** CALL_API 用 payload.taskId 区分；其它动作无额外身份键。 */
    private static String actionIdentity(AgentAction a) {
        if (a == null || a.payload() == null || a.payload().isEmpty()) {
            return null;
        }
        Object taskId = a.payload().get("taskId");
        if (taskId != null && StringUtils.hasText(String.valueOf(taskId))) {
            return "taskId:" + taskId;
        }
        Object reportId = a.payload().get("reportId");
        if (reportId != null && StringUtils.hasText(String.valueOf(reportId))) {
            return "reportId:" + reportId;
        }
        return null;
    }

    private static boolean eq(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static final String PLAIN_TEXT_RULE =
            """
            输出格式（必须遵守）：
            1) 先写思考，再写回答，严格使用下面两个标记（各占一行）：
            【思考】
            （3～8 条短句：你如何理解问题、关注哪些信息、准备给出什么建议；不要写最终分节标题）
            【回答】
            （给健管师看的正式回复）
            2) 【回答】部分使用纯中文分段，不要使用 Markdown。
            3) 禁止：# 标题、**加粗**、表格（|）、代码块、任务勾选框（- [ ]）、水平线（---）。
            4) 分层用「一、二、三」，每个分节标题单独占一行；标题下一行立刻写「1. 2. 3.」，中间不要空行。
            5) 条目用「1. 2. 3.」或「·」，每个条目单独一行、条目之间不要空行；上一节最后一条与下一节标题之间空一行。
            6) 指标对比直接写成「血压：180/99 mmHg，明显偏高」这类短句，不要画表。
            7) 病种一律用中文（糖尿病、高血压等），禁止输出 diabetes / hypertension 等英文 code。
            """;

    private static String buildOrgSystemPrompt(String orgBriefText) {
        return """
                你是 Healix 灵犀，健管师机构工作台（智能驾驶舱）的 AI 协作者。
                当前处于机构级会话（未绑定具体患者）。
                规则：
                1. 只能依据下方「机构摘要」回答，禁止编造名单外的患者、人数或标签。
                2. 协助安排今日优先、解释任务类型与处理建议；不做诊断或处方。
                3. 需要针对某位患者深入处理时，请提示用户点击回复下方的患者按钮，或在左侧「今日优先」点选。
                4. 回答简洁、结构化，使用中文。
                5. 禁止使用「红人」「红标」「依从红人」「红灯患者」等说法，也不要用同义改写暗示这类标签。
                %s

                机构摘要：
                %s
                """
                .formatted(PLAIN_TEXT_RULE, StringUtils.hasText(orgBriefText) ? orgBriefText : "（暂无数据）");
    }

    private static String buildSystemPrompt(
            String displayName, CarePlanContext patientCtx, String focusText) {
        return """
                你是 Healix 灵犀（CARE_COPILOT），健管师工作台的 AI 协作者，协助医护人员管理患者健康。
                规则：
                1. 仅提供健康管理建议，不做医学诊断或处方。
                2. 优先结合「焦点快照」中的评估标签、档案完整度、依从性与开放任务给出下一步建议。
                3. 可解读指标趋势、生活方式建议；涉及完整方案生成请提示使用「制定管理方案」。
                4. 回答简洁、结构化，使用中文。
                5. 「开放任务 / 超期任务」只能依据焦点快照里标注的开放任务列出；快照未出现的任务类型（如报告审阅、打卡跟进）不得臆造为待办或超期。已发布/已跳过的管理报告不等于待审阅；若用户问报告，说明可在报告页查看已发布记录，而不是催办已结案的审阅单。
                %s

                当前患者：%s
                焦点快照：
                %s
                档案与观测摘要：
                %s
                """
                .formatted(
                        PLAIN_TEXT_RULE,
                        displayName,
                        StringUtils.hasText(focusText) ? focusText : "（暂无）",
                        JsonUtils.toJson(patientCtx.snapshot()));
    }

    private static String fallbackReply(boolean orgSession) {
        if (orgSession) {
            return "灵犀暂时不可用。请先查看左侧今日优先名单，或点击「刷新今日建议」。";
        }
        return "灵犀暂时不可用（LLM 未启用或调用失败）。您可以尝试使用「制定管理方案」快捷能力。";
    }

    /** 拆分模型输出的思考区与回答区；无标记时整段视为回答。 */
    static ThinkAnswer splitThinkAnswer(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new ThinkAnswer(null, null);
        }
        String text = raw.replace("\r\n", "\n").trim();
        int thinkIdx = indexOfMarker(text, "【思考】");
        int answerIdx = indexOfMarker(text, "【回答】");
        if (thinkIdx >= 0 && answerIdx > thinkIdx) {
            String thinking = text.substring(thinkIdx + "【思考】".length(), answerIdx).trim();
            String answer = text.substring(answerIdx + "【回答】".length()).trim();
            return new ThinkAnswer(blankToNull(thinking), blankToNull(answer));
        }
        if (answerIdx >= 0) {
            String before = text.substring(0, answerIdx).trim();
            String answer = text.substring(answerIdx + "【回答】".length()).trim();
            if (before.startsWith("【思考】")) {
                before = before.substring("【思考】".length()).trim();
            }
            return new ThinkAnswer(blankToNull(before), blankToNull(answer));
        }
        if (thinkIdx >= 0) {
            String thinking = text.substring(thinkIdx + "【思考】".length()).trim();
            return new ThinkAnswer(blankToNull(thinking), null);
        }
        return new ThinkAnswer(null, text);
    }

    private static int indexOfMarker(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx >= 0) {
            return idx;
        }
        return text.indexOf(marker.replace('【', '[').replace('】', ']'));
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private static String truncate(String s, int max) {
        if (!StringUtils.hasText(s) || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    record ThinkAnswer(String thinking, String answer) {}
}
