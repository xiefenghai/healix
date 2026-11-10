package com.healix.agent.handler;

import com.healix.agent.support.SkillMarkdown;
import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.agent.support.AgentReplyPlainText;
import com.healix.agent.support.AnswerTokenStreamer;
import com.healix.agent.tool.StaffBizTools;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.cockpit.dto.CockpitFocusDto;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通用闲聊 / 驾驶舱对话（由 StaffAgentTools.answerGeneralChat 调用）。
 * 统一走 LLM + org-system / patient-system 规则，无关键词短路。
 */
@Component
@RequiredArgsConstructor
public class GeneralChatHandler {

    private final LlmClient llmClient;
    private final CarePlanContextService contextService;
    private final StaffBizTools staffBizTools;

    public AgentResponse execute(AgentHandlerContext ctx) {
        return executeStream(ctx, null);
    }

    public AgentResponse executeStream(AgentHandlerContext ctx, Consumer<AgentStreamEvent> sink) {
        AgentChatCommand cmd = ctx.command();
        boolean orgSession = !StringUtils.hasText(cmd.peopleId());
        String systemPrompt;
        List<AgentAction> actions = new ArrayList<>();
        boolean noPrimaryTeam = false;

        if (orgSession) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("准备机构级上下文…"));
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("loadOrgBrief", "running", "读取今日优先与待办摘要"));
            StaffBizTools.OrgBriefBundle orgBrief =
                    staffBizTools.loadOrgBrief(cmd.tenantId(), cmd.orgId(), cmd.staffId(), cmd.message());
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.tool(
                            "loadOrgBrief",
                            "done",
                            StringUtils.hasText(orgBrief.text()) ? "已加载机构摘要" : "摘要为空"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingStart("整理机构级会话目标…"));
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.thinkingDelta("依据今日优先名单回答，禁止编造名单外患者。\n"));
            if (orgBrief.actions() != null) {
                actions.addAll(orgBrief.actions());
            }
            noPrimaryTeam =
                    orgBrief.summary() != null && orgBrief.summary().getPrimaryCareTeamCount() <= 0;
            boolean hasPatientButtons = actions.stream()
                    .anyMatch(a -> a != null && "FOCUS_PATIENT".equals(a.type()));
            systemPrompt = buildOrgSystemPrompt(orgBrief.text(), hasPatientButtons);
        } else {
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("loadFocusSnapshot", "running", "读取评估/完整度/依从/任务"));
            StaffBizTools.FocusBundle focusBundle =
                    staffBizTools.loadFocusContext(cmd.tenantId(), cmd.orgId(), cmd.peopleId());
            CockpitFocusDto focus = focusBundle.focus();
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.tool(
                            "loadFocusSnapshot",
                            "done",
                            StringUtils.hasText(focusBundle.text()) ? "已加载焦点快照" : "焦点为空"));

            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("loadPatientContext", "running", "读取档案与观测摘要"));
            CarePlanContext patientCtx = contextService.load(cmd.tenantId(), cmd.peopleId());
            // 把现行方案饮水目标写入快照，避免助手另编与 PDF 冲突的毫升区间
            if (focus != null
                    && focus.getWaterTargetMl() != null
                    && focus.getWaterTargetMl() > 0
                    && patientCtx.snapshot() != null) {
                patientCtx.snapshot().put("activeCarePlanWaterTargetMl", focus.getWaterTargetMl());
            }
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
            actions.addAll(staffBizTools.suggestPatientActions(cmd.peopleId(), focus));
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
            String suggestedTeamName = StaffBizTools.extractSuggestedCareTeamName(cmd.message());
            boolean createTeamIntent = StaffBizTools.asksCreateCareTeamIntent(cmd.message());
            if (noPrimaryTeam) {
                // 未建组：建组为主、批量分配面板置灰预告（顺序与视觉主次一致）
                ensureCreateCareTeamAction(actions, reply, suggestedTeamName);
                ensureUnassignedWorkbenchAction(actions, reply, true);
                prioritizeNoTeamActions(actions);
                lockUnassignedWorkbenchActions(actions);
            } else {
                // 已有组：批量分配面板 / 再建组入口按话术与意图补齐
                ensureUnassignedWorkbenchAction(actions, reply, false);
                if (createTeamIntent || replyMentionsCreateCareTeam(reply)) {
                    ensureCreateCareTeamAction(actions, reply, suggestedTeamName);
                }
            }
            // 明确建组指令：统一话术为「已打开面板」；有组名则写入预填说明（勿说已创建成功）
            if (createTeamIntent) {
                reply =
                        StringUtils.hasText(suggestedTeamName)
                                ? "好的，已为您打开创建面板并填写名称「"
                                        + suggestedTeamName
                                        + "」，请点击确认创建。"
                                : "好的，已为您打开创建面板，请填写名称并确认创建。";
            }
            reply = alignReplyWithVisibleActions(reply, actions);
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

    /** 回复提到批量分配面板时，保证只有面板入口（与文案承诺一致；去掉旁路姓名 chip）。 */
    static void ensureUnassignedWorkbenchAction(List<AgentAction> actions, String reply) {
        ensureUnassignedWorkbenchAction(actions, reply, false);
    }

    /**
     * @param disabledWhenNoTeam 未建组时面板置灰（仍展示，防呆不可点）
     */
    static void ensureUnassignedWorkbenchAction(
            List<AgentAction> actions, String reply, boolean disabledWhenNoTeam) {
        if (actions == null || !StringUtils.hasText(reply)) {
            return;
        }
        boolean mentionsPanel =
                reply.contains("打开批量分配面板")
                        || reply.contains("打开批量分组面板")
                        || reply.contains("批量分配面板")
                        || reply.contains("批量分组面板")
                        || reply.contains("批量分配患者")
                        || reply.contains("批量分配");
        if (!mentionsPanel) {
            return;
        }
        // 批量分配操作指引：气泡里只留面板按钮，姓名 chip 会抢焦点且常被截成怪字
        actions.removeIf(a -> a != null && "FOCUS_PATIENT".equals(a.type()));
        boolean has =
                actions.stream()
                        .anyMatch(
                                a ->
                                        a != null
                                                && "NAVIGATE".equals(a.type())
                                                && "unassigned-workbench".equals(a.path()));
        if (has) {
            AgentAction panel = null;
            for (int i = 0; i < actions.size(); i++) {
                AgentAction a = actions.get(i);
                if (a != null
                        && "NAVIGATE".equals(a.type())
                        && "unassigned-workbench".equals(a.path())) {
                    panel = actions.remove(i);
                    break;
                }
            }
            if (panel != null) {
                panel = normalizeUnassignedPanelAction(panel, disabledWhenNoTeam);
                // 若同条已有建组入口，面板放后面；否则面板置顶
                boolean hasCreate =
                        actions.stream()
                                .anyMatch(
                                        a ->
                                                a != null
                                                        && "NAVIGATE".equals(a.type())
                                                        && "create-care-team".equals(a.path()));
                if (hasCreate) {
                    actions.add(panel);
                } else {
                    actions.add(0, panel);
                }
            }
            return;
        }
        boolean hasCreate =
                actions.stream()
                        .anyMatch(
                                a ->
                                        a != null
                                                && "NAVIGATE".equals(a.type())
                                                && "create-care-team".equals(a.path()));
        AgentAction panel =
                AgentAction.openUnassignedWorkbench("打开批量分配面板", disabledWhenNoTeam);
        if (hasCreate) {
            actions.add(panel);
        } else {
            actions.add(0, panel);
        }
    }

    /** 统一面板文案为「打开批量分配面板」，并按需打上 disabled。 */
    static AgentAction normalizeUnassignedPanelAction(AgentAction panel, boolean disabled) {
        if (panel == null) {
            return AgentAction.openUnassignedWorkbench("打开批量分配面板", disabled);
        }
        return AgentAction.openUnassignedWorkbench("打开批量分配面板", disabled);
    }

    static boolean replyMentionsCreateCareTeam(String reply) {
        if (!StringUtils.hasText(reply)) {
            return false;
        }
        return reply.contains("去创建健管组")
                || reply.contains("去创建我的健管组")
                || reply.contains("创建健管组")
                || reply.contains("再建一个组")
                || reply.contains("新建健管组");
    }

    /** 保证「去创建健管组」真实挂在下方（未建组 / 再建组）。 */
    static void ensureCreateCareTeamAction(List<AgentAction> actions, String reply) {
        ensureCreateCareTeamAction(actions, reply, null);
    }

    /** @param suggestedName 用户话术中的组名，非空时写入 payload.name 供前端预填 */
    static void ensureCreateCareTeamAction(
            List<AgentAction> actions, String reply, String suggestedName) {
        if (actions == null) {
            return;
        }
        boolean has =
                actions.stream()
                        .anyMatch(
                                a ->
                                        a != null
                                                && "NAVIGATE".equals(a.type())
                                                && "create-care-team".equals(a.path()));
        String label =
                StringUtils.hasText(suggestedName)
                        ? "打开创建面板"
                        : (StringUtils.hasText(reply)
                                        && (reply.contains("再建") || reply.contains("再创建"))
                                ? "去创建健管组"
                                : "去创建我的健管组");
        if (!has) {
            actions.add(0, AgentAction.createCareTeam(label, suggestedName));
            return;
        }
        // 统一可点文案；若动作尚无 name 而用户给了组名，补上预填
        for (int i = 0; i < actions.size(); i++) {
            AgentAction a = actions.get(i);
            if (a == null
                    || !"NAVIGATE".equals(a.type())
                    || !"create-care-team".equals(a.path())) {
                continue;
            }
            String cur = a.label() == null ? "" : a.label();
            Object existingName = a.payload() == null ? null : a.payload().get("name");
            boolean needName =
                    StringUtils.hasText(suggestedName)
                            && (existingName == null
                                    || !StringUtils.hasText(String.valueOf(existingName)));
            if (!cur.contains("创建") || cur.contains("左侧") || needName) {
                String keepLabel =
                        StringUtils.hasText(suggestedName)
                                ? "打开创建面板"
                                : (cur.contains("创建") && !cur.contains("左侧") ? cur : label);
                String name =
                        needName
                                ? suggestedName
                                : (existingName == null ? null : String.valueOf(existingName));
                actions.set(i, AgentAction.createCareTeam(keepLabel, name));
            }
        }
    }

    /** 未建组：建组入口永远排在批量面板之前。 */
    static void prioritizeNoTeamActions(List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        AgentAction create = null;
        AgentAction panel = null;
        List<AgentAction> rest = new ArrayList<>();
        for (AgentAction a : actions) {
            if (a == null) {
                continue;
            }
            if ("NAVIGATE".equals(a.type()) && "create-care-team".equals(a.path()) && create == null) {
                create = a;
            } else if ("NAVIGATE".equals(a.type())
                    && "unassigned-workbench".equals(a.path())
                    && panel == null) {
                panel = a;
            } else {
                rest.add(a);
            }
        }
        actions.clear();
        if (create != null) {
            actions.add(create);
        }
        if (panel != null) {
            actions.add(panel);
        }
        actions.addAll(rest);
    }

    /** 未建组兜底：凡批量分配面板一律置灰 + 统一文案。 */
    static void lockUnassignedWorkbenchActions(List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (int i = 0; i < actions.size(); i++) {
            AgentAction a = actions.get(i);
            if (a == null
                    || !"NAVIGATE".equals(a.type())
                    || !"unassigned-workbench".equals(a.path())) {
                continue;
            }
            actions.set(i, AgentAction.openUnassignedWorkbench("打开批量分配面板", true));
        }
    }

    /**
     * 最终兜底：回复里点名的下方按钮必须在 actions 里存在；否则改写指引，杜绝文案/按钮错位。
     */
    static String alignReplyWithVisibleActions(String reply, List<AgentAction> actions) {
        if (!StringUtils.hasText(reply) || actions == null) {
            return reply;
        }
        boolean hasCreate =
                actions.stream()
                        .anyMatch(
                                a ->
                                        a != null
                                                && "NAVIGATE".equals(a.type())
                                                && "create-care-team".equals(a.path()));
        boolean hasPanel =
                actions.stream()
                        .anyMatch(
                                a ->
                                        a != null
                                                && "NAVIGATE".equals(a.type())
                                                && "unassigned-workbench".equals(a.path()));
        String out = reply;
        if (!hasCreate) {
            out = out.replace("点本回复下方的「去创建健管组」", "点左侧「先创建我的健管组」");
            out = out.replace("点击本回复下方的「去创建健管组」", "点击左侧「先创建我的健管组」");
            out = out.replace("点下方的「去创建健管组」", "点左侧「先创建我的健管组」");
            out = out.replace("点下方「去创建健管组」", "点左侧「先创建我的健管组」");
            out = out.replace("下方的「去创建健管组」", "左侧「先创建我的健管组」");
            out = out.replace("点下方「去创建我的健管组」", "点左侧「先创建我的健管组」");
        }
        if (!hasPanel) {
            out = out.replace("点本回复下方的「打开批量分配面板」", "先完成建组后再批量分配");
            out = out.replace("点击本回复下方的「打开批量分配面板」", "先完成建组后再批量分配");
            out = out.replace("点下方「打开批量分配面板」", "先完成建组后再批量分配");
            out = out.replace("下方的「打开批量分配面板」", "建好组后的批量分配入口");
            out = out.replace("点本回复下方的「打开批量分组面板」", "先完成建组后再批量分配");
            out = out.replace("点击本回复下方的「打开批量分组面板」", "先完成建组后再批量分配");
            out = out.replace("点下方「打开批量分组面板」", "先完成建组后再批量分配");
            out = out.replace("下方的「打开批量分组面板」", "建好组后的批量分配入口");
        }
        return out.replaceAll("\n{3,}", "\n\n").trim();
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
        out = out.replaceAll("\n{3,}", "\n\n").trim();
        return out;
    }

    private static List<AgentAction> dedupeActions(List<AgentAction> actions) {
        List<AgentAction> out = new ArrayList<>();
        for (AgentAction a : actions) {
            boolean dup = out.stream()
                    .anyMatch(x -> x.type().equals(a.type())
                            && eq(x.path(), a.path())
                            && eq(x.peopleId(), a.peopleId())
                            && eq(x.label(), a.label())
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

    private static String buildOrgSystemPrompt(String orgBriefText, boolean hasPatientButtons) {
        boolean priorityEmpty = !StringUtils.hasText(orgBriefText) || orgBriefText.contains("优先名单为空");
        boolean noPrimaryTeam =
                StringUtils.hasText(orgBriefText) && orgBriefText.contains("主责健管组数=0");
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
        if (noPrimaryTeam) {
            nextStepRule +=
                    " 若用户问单据录入/体检/检验怎么录：先说明底部「单据录入」拍照或选中患者后右侧「录入数据」手工填，"
                            + "再补一句可点本回复下方「去创建我的健管组」；严禁只回「请先建组」而不讲路径。";
        }
        String idleRule = priorityEmpty
                ? "6. 今日待办/优先名单为空时：一两句说明暂无紧急事项即可，引导新建患者或查看我的患者；"
                        + "禁止每次复读「待办0、超期0」并展开一、二、三长报告。"
                : "6. 有待办时再按摘要给办理顺序；不要堆砌与用户问题无关的状态复读。";
        String brief = StringUtils.hasText(orgBriefText) ? orgBriefText : "（暂无数据）";
        String tpl =
                SkillMarkdown.loadClasspath(
                        "skills/general-chat/org-system.md",
                        "你是 Healix 灵犀。\n{{NEXT_STEP_RULE}}\n{{IDLE_RULE}}\n机构摘要：\n{{ORG_BRIEF}}");
        tpl = SkillMarkdown.apply(tpl, "NEXT_STEP_RULE", nextStepRule);
        tpl = SkillMarkdown.apply(tpl, "IDLE_RULE", idleRule);
        return SkillMarkdown.apply(tpl, "ORG_BRIEF", brief);
    }

    private static String buildSystemPrompt(
            String displayName, CarePlanContext patientCtx, String focusText) {
        String tpl =
                SkillMarkdown.loadClasspath(
                        "skills/general-chat/patient-system.md",
                        "你是 Healix 灵犀。\n当前患者：{{DISPLAY_NAME}}\n焦点快照：\n{{FOCUS_TEXT}}\n档案与观测摘要：\n{{PATIENT_SNAPSHOT}}");
        tpl = SkillMarkdown.apply(tpl, "DISPLAY_NAME", displayName);
        tpl = SkillMarkdown.apply(
                tpl, "FOCUS_TEXT", StringUtils.hasText(focusText) ? focusText : "（暂无）");
        return SkillMarkdown.apply(tpl, "PATIENT_SNAPSHOT", JsonUtils.toJson(patientCtx.snapshot()));
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
