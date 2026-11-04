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
        // 机构会话：轻量意图不跑「产品说明书」长链路
        if (orgSession) {
            LightTone tone = classifyLightTone(cmd.message());
            if (tone != LightTone.NONE) {
                return shortOrgLightReply(ctx, tone, sink);
            }
            OrgGuideIntent guide = classifyOrgGuideIntent(cmd.message());
            if (guide != OrgGuideIntent.NONE) {
                return shortOrgGuideReply(ctx, guide, sink);
            }
        }
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
            actions.addAll(orgBrief.actions());
            boolean hasPatientButtons = actions.stream()
                    .anyMatch(a -> a != null && "FOCUS_PATIENT".equals(a.type()));
            systemPrompt = buildOrgSystemPrompt(orgBrief.text(), hasPatientButtons);
            if (StaffBizTools.wantsCompleteTask(cmd.message())) {
                systemPrompt += hasPatientButtons
                        ? """

                        用户想办结今日待办：机构会话不能直接关单。请引导健管师先点击本回复下方带姓名的按钮切入患者，
                        再在患者会话里用「确认办结」打开填单弹窗（填完提交才写库）。
                        """
                        : """

                        用户想办结今日待办：机构会话不能直接关单，且本回复下方不会挂任何患者按钮。
                        请引导到左侧「我的患者」点选后再办结。严禁写「点击下方患者按钮」「回复下方的患者按钮」。
                        """;
            }
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

        if (orgSession) {
            boolean hasPatientButtons = actions.stream()
                    .anyMatch(a -> a != null && "FOCUS_PATIENT".equals(a.type()));
            // 模型偶发仍写「点下方患者按钮」：无真实动作时清掉误导句
            if (!hasPatientButtons) {
                reply = stripFakePatientButtonHints(reply);
            }
        } else {
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

    /** 无 FOCUS_PATIENT 动作时，去掉「点下方患者按钮」类空指引。 */
    static String stripFakePatientButtonHints(String reply) {
        if (!StringUtils.hasText(reply)) {
            return reply;
        }
        String out = reply;
        out = out.replaceAll(
                "[^\n]*点击[^。\\n]{0,24}下方[^。\\n]{0,16}患者按钮[^。\\n]*。?", "");
        out = out.replaceAll(
                "[^\n]*回复下方的患者按钮[^。\\n]*。?", "");
        out = out.replaceAll(
                "[^\n]*点(?:击)?本?回复下方[^。\\n]{0,20}按钮[^。\\n]*。?", "");
        // 压缩多空行
        out = out.replaceAll("\n{3,}", "\n\n").trim();
        return out;
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
                        不要 Markdown，不要诊断或处方，提醒按时用药与打卡即可。
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
        m.put("lifestyle", focus.getLifestyleSummary());
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

    /** 机构会话：默认短答；勿逼模型每次写「一、二、三」报告。 */
    private static final String ORG_PLAIN_TEXT_RULE =
            """
            输出格式（必须遵守）：
            1) 可选用【思考】【回答】标记；【回答】给健管师看。
            2) 纯中文，不要 Markdown（禁止 #、**、表格、代码块）。
            3) 默认一两句口语短回（约 30～60 字）；只有用户明确要求「安排今日工作 / 解释多条待办」时，才用「一、二、三」分节。
            4) 禁止主动抛出「评估/用药不在协助范围」等免责句；仅当用户真的在问医学结论、用药建议或评估解读时再简要说明边界。
            5) 病种用中文，禁止输出英文病种 code。
            """;

    /**
     * 机构会话轻量意图：语气词 / 乱码不进 LLM、不查库。
     * 包内可见便于单测。
     */
    enum LightTone {
        NONE,
        ACK,
        GIBBERISH
    }

    /** 「你能干什么 / 我现在要干什么 / 任务类型」等场景问答。 */
    enum OrgGuideIntent {
        NONE,
        CAPABILITY,
        NEXT_STEP,
        TASK_TYPES
    }

    static LightTone classifyLightTone(String message) {
        if (!StringUtils.hasText(message)) {
            return LightTone.GIBBERISH;
        }
        String t = message.trim();
        if (t.matches(
                "^(嗯+|哦+|噢+|呃+|啊+|哎+|好的?|行|可以|收到|知道了|明白了|了解了?|嗯嗯+|ok|OK|Okay|okay|谢谢|感谢|哈哈+|呵呵+|嘻嘻+)[.。!！?？…~～\\s]*$")) {
            return LightTone.ACK;
        }
        // 纯数字/符号串（排除像手机号）
        if (t.length() <= 32
                && t.matches("^[\\d\\s._\\-]+$")
                && !t.matches(".*1[3-9]\\d{9}.*")) {
            return LightTone.GIBBERISH;
        }
        // 同一字符狂按（aaaa / 1111）
        if (t.length() >= 4 && t.length() <= 24) {
            long distinct = t.chars().distinct().count();
            if (distinct <= 2 && t.matches("^[a-zA-Z0-9]+$")) {
                return LightTone.GIBBERISH;
            }
        }
        return LightTone.NONE;
    }

    static OrgGuideIntent classifyOrgGuideIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return OrgGuideIntent.NONE;
        }
        // 去掉空白，兼容「你能做点啥」「你 能 干 什么」
        String n = message.trim().replaceAll("\\s+", "");
        // 「你能做点啥」：做与啥之间可夹字，避免旧正则只认「做啥」连写
        if (n.contains("能做点啥")
                || n.contains("能干点啥")
                || n.contains("能干什么")
                || n.contains("能做啥")
                || n.contains("能干啥")
                || n.contains("可以干什么")
                || n.contains("可以做啥")
                || n.contains("你会什么")
                || n.contains("你有什么能力")
                || n.contains("能帮我什么")
                || n.contains("有什么功能")
                || n.contains("帮我省什么")
                || n.equals("功能介绍")
                || n.equals("帮助")
                || n.matches(".*(你|您).{0,8}(能|会|可以).{0,8}(干|做).{0,6}(什么|啥).*")) {
            return OrgGuideIntent.CAPABILITY;
        }
        if (n.contains("任务类型")
                || n.contains("解释任务")
                || n.contains("什么任务")
                || n.contains("有哪些任务")) {
            return OrgGuideIntent.TASK_TYPES;
        }
        if (n.contains("先忙什么")
                || n.contains("从哪开始")
                || n.contains("怎么开始")
                || n.contains("无事可做")
                || n.equals("下一步")
                || n.matches(".*(我)?(现在|今天|接下来)?.{0,4}(要|该|应该)?(干|做)(什么|啥).*")) {
            return OrgGuideIntent.NEXT_STEP;
        }
        return OrgGuideIntent.NONE;
    }

    private AgentResponse shortOrgLightReply(
            AgentSkillContext ctx, LightTone tone, Consumer<AgentStreamEvent> sink) {
        String reply =
                tone == LightTone.ACK
                        ? "收到。需要帮忙时随时叫我，或点下方按钮开始。"
                        : "没太明白您的意思。您是想建档，还是去看看我的患者？可直接点下方按钮告诉我。";
        return finishShortOrg(ctx, sink, "ORG_CHAT_LIGHT", reply, orgGuideActions());
    }

    private AgentResponse shortOrgGuideReply(
            AgentSkillContext ctx, OrgGuideIntent guide, Consumer<AgentStreamEvent> sink) {
        AgentChatCommand cmd = ctx.command();
        if (guide == OrgGuideIntent.CAPABILITY) {
            // 纯场景话术：不查库、不推送活动轨迹事件
            String reply =
                    """
                    我是您的贴身健管助理，最擅长帮您做这三件事：
                    1. 快速建档：直接把信息发我（如：张三 女 45岁 13800000000），我帮您建档。
                    2. 写方案与话术：例如「帮我写一份血糖控制方案」或「写段回访话术」。
                    3. 盯重点：提醒今天该回访谁、谁指标异常，或帮您总结某位患者的画像要点。
                    点下方按钮开始，或直接把需求发给我试试。""";
            return finishShortOrg(ctx, sink, "ORG_CHAT_CAPABILITY", reply, orgGuideActions());
        }

        if (guide == OrgGuideIntent.TASK_TYPES) {
            StaffBizTools.MinePeek peek =
                    staffBizTools.peekMineContext(cmd.tenantId(), cmd.orgId());
            boolean idle = peek.openTaskCount() <= 0 && peek.overdueCount() <= 0;
            String reply =
                    """
                    好，常见任务类型大致这几类：
                    1. 随访跟进：按方案周期回访，了解近况和打卡，约下次沟通。
                    2. 方案到期：服务周期快结束，提前联系续约或调整方案。
                    3. 打卡提醒：客户连续没打卡，提醒并了解原因。
                    4. 资料完善：档案信息缺失，补齐后再正常跟进。
                    """
                            + (idle
                                    ? "您今天这几类都没有，优先名单是空的。可以直接问我「如果明天想重点跟进糖尿病患者，该怎么定任务」，或点下方先建个档试试。"
                                    : "需要处理时，点左侧「立即处理」或下方按钮选人跟进。");
            List<AgentAction> actions = new ArrayList<>();
            actions.add(AgentAction.triggerCapability("新建患者", "CREATE_ARCHIVE", null));
            if (!idle) {
                actions.add(AgentAction.setCockpitTab("查看立即处理", "urgent"));
            } else {
                actions.add(AgentAction.setCockpitTab("查看我的患者", "mine"));
            }
            return finishShortOrg(ctx, sink, "ORG_CHAT_TASK_TYPES", reply, actions);
        }

        // NEXT_STEP：静默扫一眼「我的患者」，有人就点名（不刷工具轨迹）
        StaffBizTools.MinePeek peek =
                staffBizTools.peekMineContext(cmd.tenantId(), cmd.orgId());
        String reply;
        List<AgentAction> actions = new ArrayList<>();
        var first = peek.firstMine();
        if (peek.openTaskCount() > 0 || peek.overdueCount() > 0) {
            reply = "今天还有待办"
                    + (peek.overdueCount() > 0 ? "（含超期 " + peek.overdueCount() + "）" : "")
                    + "。建议先点左侧「立即处理」，或点下方「查看我的患者」选人跟进；也可以新建一位患者。";
            actions.add(AgentAction.setCockpitTab("查看立即处理", "urgent"));
            actions.add(AgentAction.triggerCapability("新建患者", "CREATE_ARCHIVE", null));
        } else if (first != null && StringUtils.hasText(first.getPeopleId())) {
            String name =
                    StringUtils.hasText(first.getDisplayName()) ? first.getDisplayName() : "这位患者";
            reply = "今天暂时没有紧急待办。我看到您名下有「"
                    + name
                    + "」，可以点下方继续服务；也可以先建一位新患者。";
            actions.add(AgentAction.focusPatient("为" + name + "健管服务", first.getPeopleId(), false));
            actions.add(AgentAction.triggerCapability("新建患者", "CREATE_ARCHIVE", null));
        } else {
            reply = "今天暂时没有紧急待办。闲不下来的话，点下方「新建患者」接待第一位客户，或到左侧看看「我的患者」。";
            actions = orgGuideActions();
        }
        return finishShortOrg(ctx, sink, "ORG_CHAT_NEXT_STEP", reply, actions);
    }

    /** 轻量短回：只推气泡正文，不推 thinking/tool，避免闲聊也刷「7步轨迹」。 */
    private AgentResponse finishShortOrg(
            AgentSkillContext ctx,
            Consumer<AgentStreamEvent> sink,
            String intent,
            String reply,
            List<AgentAction> actions) {
        if (sink != null) {
            new AnswerTokenStreamer(sink).finish(reply);
        }
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.GENERAL_CHAT.name(),
                intent,
                reply,
                actions,
                List.of(),
                null);
    }

    /** 意图不清时的可点引路（最多 2 个）。 */
    private static List<AgentAction> orgGuideActions() {
        List<AgentAction> out = new ArrayList<>();
        out.add(AgentAction.triggerCapability("新建患者", "CREATE_ARCHIVE", null));
        out.add(AgentAction.setCockpitTab("查看我的患者", "mine"));
        return out;
    }

    private static String buildOrgSystemPrompt(String orgBriefText, boolean hasPatientButtons) {
        // 有 FOCUS_PATIENT 动作才可以说「点下方姓名」；名单空时绝不能编「下方患者按钮」
        boolean priorityEmpty = !StringUtils.hasText(orgBriefText) || orgBriefText.contains("优先名单为空");
        String nextStepRule;
        if (hasPatientButtons && !priorityEmpty) {
            nextStepRule =
                    "3. 需要针对某位患者深入处理时，可提示：点击本回复下方带姓名的按钮（例如「张三」）切入该患者，"
                            + "或到左侧「我的患者」点选。不要使用含糊的「患者按钮」三字单独指代。";
        } else if (hasPatientButtons) {
            nextStepRule =
                    "3. 本回复下方会挂姓名按钮，可提示点击下方姓名切入；左侧「立即处理」可能暂无待办，"
                            + "勿写「去今日优先点选」造成左右矛盾。不要说含糊的「患者按钮」。";
        } else {
            nextStepRule =
                    "3. 本回复下方可提示点「新建患者」等行动按钮；也可说到左侧「我的患者」选人。"
                            + "严禁写「点击下方患者按钮」「回复下方的患者按钮」；"
                            + "也不要写「到今日优先点选」——当前名单为空时左侧今日优先也是空的。";
        }
        String idleRule = priorityEmpty
                ? "6. 今日待办/优先名单为空时：一两句说明暂无紧急事项即可，引导新建患者或查看我的患者；"
                        + "禁止每次复读「待办0、超期0」并展开一、二、三长报告。"
                : "6. 有待办时再按摘要给办理顺序；不要堆砌与用户问题无关的状态复读。";
        return """
                你是 Healix 灵犀，健管师机构工作台（智能驾驶舱）的 AI 协作者。
                当前处于机构级会话（未绑定具体患者）。
                规则：
                1. 只能依据下方「机构摘要」回答，禁止编造名单外的患者、人数或标签；摘要里有打卡排名、到期方案等统计时，直接引用其中的姓名、百分比和日期作答，摘要没有的统计如实说明暂不支持。
                2. 协助安排今日优先、解释任务类型与处理建议；不做诊断或处方。摘要写「优先名单为空」时，不要编造具体患者姓名或待办。
                %s
                4. 回答简洁、口语化，使用中文；默认短回，不要写成工作报告。
                5. 禁止使用「红人」「红标」「依从红人」「红灯患者」等说法，也不要用同义改写暗示这类标签。
                %s
                %s

                机构摘要：
                %s
                """
                .formatted(
                        nextStepRule,
                        idleRule,
                        ORG_PLAIN_TEXT_RULE,
                        StringUtils.hasText(orgBriefText) ? orgBriefText : "（暂无数据）");
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
            return "灵犀暂时不可用。可点下方卡片开始，或点击「刷新今日建议」后重试。";
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
