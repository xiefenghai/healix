package com.healix.agent.handler;

import com.healix.agent.archive.CreateArchiveDraft;
import com.healix.agent.archive.CreateArchiveParser;
import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.exception.BusinessException;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.core.workspace.service.OrgWorkspaceService.DuplicateCandidate;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话建档（由 StaffAgentTools 调用）：抽四要素 → 查重 → 核对卡确认后写库。
 */
@Component
@RequiredArgsConstructor
public class CreateArchiveHandler {

    private final OrgWorkspaceService orgWorkspaceService;
    private final CareTeamMapper careTeamMapper;

    public AgentResponse execute(AgentHandlerContext ctx) {
        AgentChatCommand cmd = ctx.command();
        CreateArchiveDraft current = CreateArchiveParser.parse(cmd.message());
        // 芯片「建档」/「需要哪些信息」等开场话：本轮无身份要素 → 不吞历史里旧患者，避免误报「已建过档」
        CreateArchiveDraft draft = isFreshArchiveStart(cmd.message(), current)
                ? current
                : mergeFromHistory(ctx.history(), current);

        if (draft.noMobile() && !draft.hasMobile()) {
            return needInfo(
                    ctx,
                    draft,
                    "可以先标「暂无本人手机号」，但本版对话建档仍需要一个本人手机号才能入库。"
                            + "把手机号发给我即可，大白话也行。");
        }
        if (!draft.isComplete()) {
            return needInfo(ctx, draft, filledOrMissingPrompt(draft));
        }

        try {
            List<DuplicateCandidate> duplicates = orgWorkspaceService.findPossibleDuplicates(
                    cmd.orgId(), draft.name(), draft.gender(), draft.birthday(), draft.mobile());
            DuplicateCandidate alreadyInOrg = duplicates.stream()
                    .filter(d -> d.inCurrentOrg() && !d.confirmRequired())
                    .findFirst()
                    .orElse(null);
            if (alreadyInOrg != null) {
                return alreadyExistsResponse(ctx, alreadyInOrg.peopleId());
            }

            List<DuplicateCandidate> needConfirm =
                    duplicates.stream().filter(DuplicateCandidate::confirmRequired).toList();
            if (!needConfirm.isEmpty()) {
                return confirmDuplicates(ctx, draft, needConfirm);
            }

            // 四要素齐全：打开右侧表单预填，由健管师点「确认创建」入库（忌静默落档）
            return needInfo(ctx, draft, filledOrMissingPrompt(draft));
        } catch (BusinessException e) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.CREATE_ARCHIVE.name(),
                    "CREATE_ARCHIVE_ERROR",
                    e.getMessage() == null ? "建档失败" : e.getMessage(),
                    List.of(),
                    List.of(),
                    null);
        }
    }

    /**
     * 患者已在本机构：顺势继续服务，不说「无需重复创建」。
     * 自己主责 → 直接引导首诊；他人主责 → 柔和说明并可改挂/协同查看。
     */
    private AgentResponse alreadyExistsResponse(AgentHandlerContext ctx, String peopleId) {
        AgentChatCommand cmd = ctx.command();
        OrgPatientListItem item = orgWorkspaceService.getOrgPatient(cmd.orgId(), peopleId);
        String name = StringUtils.hasText(item.getDisplayName()) ? item.getDisplayName() : "该患者";
        boolean hasTeam = StringUtils.hasText(item.getCareTeamId());
        String teamName = hasTeam && StringUtils.hasText(item.getCareTeamName())
                ? item.getCareTeamName()
                : null;
        // 未入组时不展示残留 assignment 上的主责名，避免「未入组却服务在你名下」
        String managerName = hasTeam && StringUtils.hasText(item.getPrimaryCareManagerName())
                ? item.getPrimaryCareManagerName()
                : null;

        String staffId = requireStaffId();
        boolean alreadyMine = hasTeam
                && StringUtils.hasText(item.getPrimaryCareManagerStaffId())
                && item.getPrimaryCareManagerStaffId().equals(staffId);

        Integer age = ageYears(item.getBirthday());
        StringBuilder reply = new StringBuilder();
        reply.append("「").append(name).append("」已在档案库中");
        if (age != null) {
            reply.append("（").append(age).append("岁）");
        }
        reply.append("。");

        List<AgentAction> actions = new ArrayList<>();

        if (alreadyMine) {
            reply.append("服务已在你名下，已为你准备好继续跟进。可直接开始首诊，或切入会话。");
            actions.add(AgentAction.openSheet("开始首诊", "intake", peopleId));
            actions.add(AgentAction.focusPatient("为" + name + "健管服务", peopleId, false));
        } else {
            List<CareTeam> myTeams = careTeamMapper.listByPrimaryCareManager(cmd.orgId(), staffId);
            List<CareTeam> candidates = assignableTeams(myTeams, item.getCareTeamId());
            boolean alreadyOnMyTeam = hasTeam
                    && StringUtils.hasText(item.getCareTeamId())
                    && myTeams != null
                    && myTeams.stream()
                            .anyMatch(t -> t != null && item.getCareTeamId().equals(t.getId()));

            if (alreadyOnMyTeam) {
                reply.append("你已在相关健管组内，可直接开始首诊或切入会话。");
                actions.add(AgentAction.openSheet("开始首诊", "intake", peopleId));
                actions.add(AgentAction.focusPatient("为" + name + "健管服务", peopleId, false));
            } else if (candidates.isEmpty()) {
                if (hasTeam) {
                    reply.append("当前由健管组「")
                            .append(teamName != null ? teamName : "未命名组")
                            .append("」跟进");
                    if (StringUtils.hasText(managerName)) {
                        reply.append("（主责：").append(managerName).append("）");
                    }
                    reply.append("。你名下暂无其他主责组可改挂，可先协同查看，或先创建自己的健管组后再接手。");
                } else {
                    reply.append("尚未加入健管组。请先创建以你为主责的健管组，再把患者分配进来。");
                }
                actions.add(AgentAction.createCareTeam("去创建健管组", null));
                actions.add(AgentAction.focusPatient("先协同查看", peopleId, false));
            } else if (candidates.size() == 1) {
                CareTeam team = candidates.get(0);
                String teamLabel = nullToEmpty(team.getName());
                if (hasTeam) {
                    reply.append("当前由健管组「")
                            .append(teamName != null ? teamName : "未命名组")
                            .append("」跟进");
                    if (StringUtils.hasText(managerName)) {
                        reply.append("（主责：").append(managerName).append("）");
                    }
                    reply.append("。如需由你接手，可改挂到「")
                            .append(teamLabel)
                            .append("」；也可先协同查看。");
                    actions.add(AgentAction.changeCareTeam("接手并改挂到" + teamLabel, peopleId, team.getId()));
                } else {
                    reply.append("尚未入组。可一键分配到你的健管组「")
                            .append(teamLabel)
                            .append("」，再做首诊；也可先协同查看。");
                    actions.add(AgentAction.changeCareTeam("分配到" + teamLabel, peopleId, team.getId()));
                }
                actions.add(AgentAction.focusPatient("先协同查看", peopleId, false));
            } else {
                // 多组：只给一个入口，进选组面板，避免芯片爆炸
                if (hasTeam) {
                    reply.append("当前由健管组「")
                            .append(teamName != null ? teamName : "未命名组")
                            .append("」跟进");
                    if (StringUtils.hasText(managerName)) {
                        reply.append("（主责：").append(managerName).append("）");
                    }
                    reply.append("。如需由你接手，请选择要改挂的健管组；也可先协同查看。");
                    actions.add(AgentAction.navigate("接手：改挂健管组", "join-care-team", peopleId));
                } else {
                    reply.append("尚未入组。请选择要挂的健管组，入组后再做首诊；也可先协同查看。");
                    actions.add(AgentAction.navigate("分配健管组", "join-care-team", peopleId));
                }
                actions.add(AgentAction.focusPatient("先协同查看", peopleId, false));
            }
        }

        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CREATE_ARCHIVE.name(),
                "CREATE_ARCHIVE_EXISTS",
                reply.toString(),
                actions,
                List.of(),
                null);
    }

    private static Integer ageYears(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        int years = Period.between(birthday, LocalDate.now()).getYears();
        return years >= 0 && years < 150 ? years : null;
    }

    private static String nullToEmpty(String s) {
        return StringUtils.hasText(s) ? s.trim() : "未命名组";
    }

    /** 可改挂/分配的主责组：排除患者已在的组。 */
    private static List<CareTeam> assignableTeams(List<CareTeam> myTeams, String currentCareTeamId) {
        if (myTeams == null || myTeams.isEmpty()) {
            return List.of();
        }
        List<CareTeam> out = new ArrayList<>();
        for (CareTeam team : myTeams) {
            if (team == null || !StringUtils.hasText(team.getId())) {
                continue;
            }
            if (StringUtils.hasText(currentCareTeamId) && currentCareTeamId.equals(team.getId())) {
                continue;
            }
            out.add(team);
        }
        return out;
    }

    private AgentResponse doCreate(AgentHandlerContext ctx, CreateArchiveDraft draft, String attachPeopleId) {
        AgentChatCommand cmd = ctx.command();
        String accountId = requireAccountId();
        String staffId = requireStaffId();
        OrgPatientListItem item = orgWorkspaceService.createArchive(
                cmd.orgId(),
                draft.name().trim(),
                attachPeopleId,
                null,
                null,
                draft.gender(),
                draft.birthday(),
                draft.mobile(),
                "SELF",
                false,
                accountId);
        String peopleId = item.getPeopleId();
        String name = StringUtils.hasText(item.getDisplayName()) ? item.getDisplayName() : draft.name();
        if (!StringUtils.hasText(name)) {
            name = "该患者";
        }
        String verb = attachPeopleId != null ? "已关联已有档案并加入本机构" : "已建档";
        List<AgentAction> actions = new ArrayList<>();
        AgentAction servePatient = AgentAction.focusPatient("为" + name + "健管服务", peopleId, false);
        String reply;
        // 标准路径：建档 → 分组 → 首诊评估 → 制定方案 …
        if (!StringUtils.hasText(item.getCareTeamId())) {
            List<CareTeam> candidates =
                    assignableTeams(careTeamMapper.listByPrimaryCareManager(cmd.orgId(), staffId), null);
            if (candidates.size() == 1) {
                CareTeam team = candidates.get(0);
                String teamLabel = nullToEmpty(team.getName());
                try {
                    orgWorkspaceService.changePatientCareTeam(
                            cmd.orgId(), peopleId, team.getId(), accountId);
                    reply = verb + "：「" + name + "」。已完成分组（「" + teamLabel + "」）。"
                            + "下一步请做首诊评估，再制定方案。";
                    actions.add(AgentAction.openSheet("开始首诊评估", "intake", peopleId));
                    actions.add(AgentAction.navigate("修改分组", "join-care-team", peopleId));
                } catch (BusinessException ex) {
                    reply = verb + "：「" + name + "」。下一步是「分组」：可一键分配到「" + teamLabel
                            + "」，完成后再做首诊评估。";
                    actions.add(AgentAction.changeCareTeam("分配到" + teamLabel, peopleId, team.getId()));
                    actions.add(AgentAction.openSheet("稍后再做首诊评估", "intake", peopleId));
                }
            } else if (candidates.isEmpty()) {
                reply = verb + "：「" + name + "」。下一步是「分组」：你名下暂无主责健管组，"
                        + "请先创建后再把患者分进去，然后做首诊评估。";
                actions.add(AgentAction.createCareTeam("去创建健管组", null));
                actions.add(AgentAction.openSheet("稍后再做首诊评估", "intake", peopleId));
            } else {
                reply = verb + "：「" + name + "」。下一步是「分组」：请选择目标健管组，"
                        + "完成后再做首诊评估、制定方案。";
                actions.add(AgentAction.navigate("去分组", "join-care-team", peopleId));
                actions.add(AgentAction.openSheet("稍后再做首诊评估", "intake", peopleId));
            }
            actions.add(servePatient);
        } else {
            reply = verb + "：「" + name + "」。分组已完成。下一步请做首诊评估，再制定方案。";
            actions.add(AgentAction.openSheet("开始首诊评估", "intake", peopleId));
            actions.add(servePatient);
        }
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CREATE_ARCHIVE.name(),
                "CREATE_ARCHIVE_OK",
                reply,
                actions,
                List.of(),
                null);
    }

    private AgentResponse confirmDuplicates(
            AgentHandlerContext ctx, CreateArchiveDraft draft, List<DuplicateCandidate> needConfirm) {
        StringBuilder sb = new StringBuilder();
        sb.append("这个本人手机号已有档案，但姓名/生日与你刚填的不一致，请确认：\n");
        List<AgentAction> actions = new ArrayList<>();
        int i = 1;
        for (DuplicateCandidate d : needConfirm) {
            sb.append(i++)
                    .append(". ")
                    .append(d.displayName())
                    .append(genderLabel(d.gender()))
                    .append(d.birthday() != null ? " · " + d.birthday() : "")
                    .append(StringUtils.hasText(d.mobileMasked()) ? " · " + d.mobileMasked() : "")
                    .append(d.inCurrentOrg() ? "（已在本机构）" : "")
                    .append('\n');
            if (d.inCurrentOrg()) {
                String dupName = StringUtils.hasText(d.displayName()) ? d.displayName() : "该患者";
                actions.add(AgentAction.focusPatient("就是" + dupName + "，去服务", d.peopleId(), false));
            } else {
                actions.add(AgentAction.createArchive(
                        "就是「" + d.displayName() + "」，挂接到本机构", archivePayload(draft, d.peopleId())));
            }
        }
        // 本人手机号租户内唯一：同号不能再建一份，勿挂会 409 的「不是同一人，新建」
        String exampleMobile = "13900139000";
        sb.append("本人手机号在本租户只能绑定一份档案。\n");
        sb.append("· 若就是上方此人：点下方按钮继续。\n");
        sb.append("· 若不是同一人：请换一个本人手机号后重新发送，例如：\n");
        sb.append("建档 ")
                .append(draft.name().trim())
                .append(genderLabel(draft.gender()).replace(" · ", " "))
                .append(' ')
                .append(draft.birthday())
                .append(' ')
                .append(exampleMobile);
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CREATE_ARCHIVE.name(),
                "CREATE_ARCHIVE_CONFIRM",
                sb.toString().trim(),
                actions,
                List.of(),
                null);
    }

    private static Map<String, Object> archivePayload(CreateArchiveDraft draft, String attachPeopleId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", draft.name().trim());
        payload.put("gender", draft.gender());
        payload.put("birthday", draft.birthday().toString());
        payload.put("mobile", draft.mobile());
        payload.put("mobileOwner", "SELF");
        payload.put("noMobile", false);
        if (StringUtils.hasText(attachPeopleId)) {
            payload.put("attachPeopleId", attachPeopleId);
        }
        return payload;
    }

    private AgentResponse needInfo(AgentHandlerContext ctx, CreateArchiveDraft draft, String prompt) {
        List<AgentAction> actions = new ArrayList<>();
        // 前端会自动拉开建档抽屉；按钮保留给「抽屉已关」时再打开，文案不再挂进度清单抢视线
        actions.add(AgentAction.openCreateArchive("打开建档表单", draftFormPayload(draft)));
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CREATE_ARCHIVE.name(),
                "CREATE_ARCHIVE_NEED_INFO",
                prompt,
                actions,
                List.of(),
                null);
    }

    /** 抽屉预填：只带已抽出的非空字段。 */
    private static Map<String, Object> draftFormPayload(CreateArchiveDraft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (draft == null) {
            return payload;
        }
        if (draft.hasName()) {
            payload.put("name", draft.name().trim());
        }
        if (draft.hasGender()) {
            payload.put("gender", draft.gender());
        }
        if (draft.hasBirthday()) {
            payload.put("birthday", draft.birthday().toString());
        }
        if (draft.hasMobile()) {
            payload.put("mobile", draft.mobile());
        }
        if (draft.noMobile()) {
            payload.put("noMobile", true);
        }
        return payload;
    }

    /** 四要素齐全后的人审确认（点 CALL_API 才建档）。 */
    private AgentResponse confirmReadyCreate(
            AgentHandlerContext ctx, CreateArchiveDraft draft, String attachPeopleId) {
        String name = draft.name().trim();
        StringBuilder sb = new StringBuilder();
        sb.append("已帮你整理好建档信息，请核对：\n");
        sb.append("· 姓名：").append(name).append('\n');
        sb.append("· 性别：").append(genderPlain(draft.gender())).append('\n');
        sb.append("· 出生：").append(draft.birthday()).append('\n');
        sb.append("· 手机：").append(draft.mobile()).append('\n');
        if (StringUtils.hasText(attachPeopleId)) {
            sb.append("\n系统里可能已有同号档案，确认后会挂接到本机构继续服务。");
        }
        sb.append("\n点「确认无误，立即建档」才会写入；有误请直接发正确信息，我会按你说的改。");
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.createArchive("确认无误，立即建档", archivePayload(draft, attachPeopleId)));
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CREATE_ARCHIVE.name(),
                "CREATE_ARCHIVE_READY",
                sb.toString().trim(),
                actions,
                List.of(),
                null);
    }

    private static String missingPrompt(CreateArchiveDraft draft) {
        boolean noneYet =
                !draft.hasName() && !draft.hasGender() && !draft.hasBirthday() && !draft.hasMobile();
        if (noneYet) {
            // 前端会自动打开右侧表单：话术只引导填表，避免再教「粘贴格式」抢焦点
            return "好的，建档表单已为您打开。请直接在右侧填写，完成后点击右下角「确认创建」即可。";
        }
        List<String> miss = missingFields(draft);
        return "还差：" + String.join("、", miss) + "。请在右侧表单补全后点「确认创建」。";
    }

    /** 已抽出姓名等字段：明确告知已回填；齐全时引导点抽屉「确认创建」。 */
    static String filledOrMissingPrompt(CreateArchiveDraft draft) {
        if (draft == null || !draft.hasName()) {
            return missingPrompt(draft == null ? new CreateArchiveDraft(null, null, null, null, false) : draft);
        }
        StringBuilder who = new StringBuilder(draft.name().trim());
        boolean hasGender = draft.hasGender();
        Integer age = ageYears(draft.birthday());
        if (hasGender || age != null) {
            who.append('（');
            if (hasGender) {
                who.append(genderPlain(draft.gender()));
            }
            if (age != null) {
                if (hasGender) {
                    who.append('，');
                }
                who.append(age).append('岁');
            }
            who.append('）');
        }
        List<String> miss = missingFields(draft);
        if (miss.isEmpty()) {
            return "收到！已为您提取并填入右侧表单："
                    + who
                    + "。请核对无误后点击右下角「确认创建」。";
        }
        return "收到！已为您提取并填入右侧表单："
                + who
                + "。还差："
                + String.join("、", miss)
                + "，请在右侧补全后点「确认创建」。";
    }

    private static List<String> missingFields(CreateArchiveDraft draft) {
        List<String> miss = new ArrayList<>();
        if (!draft.hasName()) {
            miss.add("姓名");
        }
        if (!draft.hasGender()) {
            miss.add("性别");
        }
        if (!draft.hasBirthday()) {
            miss.add("出生日期");
        }
        if (!draft.hasMobile()) {
            miss.add("本人手机号");
        }
        return miss;
    }

    private static String genderPlain(String gender) {
        if ("MALE".equals(gender)) {
            return "男";
        }
        if ("FEMALE".equals(gender)) {
            return "女";
        }
        return "未知";
    }

    private static CreateArchiveDraft mergeFromHistory(List<ChatTurn> history, CreateArchiveDraft current) {
        CreateArchiveDraft merged = new CreateArchiveDraft(null, null, null, null, false);
        if (history != null) {
            for (ChatTurn turn : history) {
                if (turn == null) {
                    continue;
                }
                if (StringUtils.hasText(turn.user())) {
                    CreateArchiveDraft parsed = CreateArchiveParser.parse(turn.user());
                    // 历史脏名（如「分组」）不得污染本轮建档预填
                    if (parsed.hasName() && CreateArchiveParser.isNoiseName(parsed.name())) {
                        parsed =
                                new CreateArchiveDraft(
                                        null,
                                        parsed.gender(),
                                        parsed.birthday(),
                                        parsed.mobile(),
                                        parsed.noMobile());
                    }
                    merged = CreateArchiveParser.merge(merged, parsed);
                }
            }
        }
        return CreateArchiveParser.merge(merged, current);
    }

    /**
     * 本轮抽不到身份要素：视为新开一档，不合并历史。
     * 多轮补全（本轮带了姓名/手机/生日/性别）仍走 {@link #mergeFromHistory}。
     */
    static boolean isFreshArchiveStart(String message, CreateArchiveDraft current) {
        if (current != null
                && (current.hasName()
                        || current.hasMobile()
                        || current.hasBirthday()
                        || current.hasGender())) {
            return false;
        }
        // 不再用「建档」等口令关键词；无身份要素即新开
        return true;
    }

    private static String requireAccountId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || !StringUtils.hasText(ctx.getAccountId())) {
            throw new BusinessException(401, "缺少账号上下文");
        }
        return ctx.getAccountId();
    }

    private static String requireStaffId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || !StringUtils.hasText(ctx.getStaffId())) {
            throw new BusinessException(401, "缺少员工上下文");
        }
        return ctx.getStaffId();
    }

    private static String genderLabel(String gender) {
        if ("MALE".equals(gender)) {
            return " · 男";
        }
        if ("FEMALE".equals(gender)) {
            return " · 女";
        }
        return "";
    }

}
