package com.healix.agent.skill;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话建档：抽四要素 → 查重 → 无冲突直接建档并切焦点；需确认时挂 CALL_API。
 *
 * <p>已在本机构时：告知健管组 / 主责健管师，并询问是否改挂到当前员工名下。
 */
@Component
@RequiredArgsConstructor
public class CreateArchiveSkill implements AgentSkill {

    private final OrgWorkspaceService orgWorkspaceService;
    private final CareTeamMapper careTeamMapper;

    @Override
    public AgentCapability capability() {
        return AgentCapability.CREATE_ARCHIVE;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.CREATE_ARCHIVE || matchesMessage(cmd.message());
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        AgentChatCommand cmd = ctx.command();
        CreateArchiveDraft draft = mergeFromHistory(ctx.history(), CreateArchiveParser.parse(cmd.message()));

        if (draft.noMobile() && !draft.hasMobile()) {
            return needInfo(
                    ctx,
                    draft,
                    "可以标「暂无本人号」，但还需要联系人手机号和归属（配偶/子女等）。"
                            + "本版对话建档请先给本人手机号，例如：建档 李敏 女 1990-05-01 13800138000");
        }
        if (!draft.isComplete()) {
            return needInfo(ctx, draft, missingPrompt(draft));
        }

        try {
            List<DuplicateCandidate> duplicates = orgWorkspaceService.findPossibleDuplicates(
                    cmd.orgId(), draft.name(), draft.birthday(), draft.mobile());
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

            String attachPeopleId = duplicates.stream()
                    .filter(d -> !d.confirmRequired())
                    .map(DuplicateCandidate::peopleId)
                    .findFirst()
                    .orElse(null);
            return doCreate(ctx, draft, attachPeopleId);
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
     * 患者已在本机构：展示当前服务归属，并询问是否改挂到当前健管师名下（不自动改组）。
     */
    private AgentResponse alreadyExistsResponse(AgentSkillContext ctx, String peopleId) {
        AgentChatCommand cmd = ctx.command();
        OrgPatientListItem item = orgWorkspaceService.getOrgPatient(cmd.orgId(), peopleId);
        String name = StringUtils.hasText(item.getDisplayName()) ? item.getDisplayName() : "该患者";
        String teamName = StringUtils.hasText(item.getCareTeamName()) ? item.getCareTeamName() : "尚未加入健管组";
        String managerName = StringUtils.hasText(item.getPrimaryCareManagerName())
                ? item.getPrimaryCareManagerName()
                : "暂无主责健管师";

        String staffId = requireStaffId();
        boolean alreadyMine = StringUtils.hasText(item.getPrimaryCareManagerStaffId())
                && item.getPrimaryCareManagerStaffId().equals(staffId);

        StringBuilder reply = new StringBuilder();
        reply.append("「")
                .append(name)
                .append("」已经建过档，无需重复新建。\n")
                .append("当前健管组：")
                .append(teamName)
                .append("\n")
                .append("主责健管师：")
                .append(managerName)
                .append("。");

        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.focusPatient("打开该患者", peopleId, false));

        if (alreadyMine) {
            reply.append("\n服务已在你名下，可直接打开患者继续首诊评估或跟进。");
            actions.add(AgentAction.openSheet("去做首诊评估", "intake", peopleId));
        } else {
            List<CareTeam> myTeams = careTeamMapper.listByPrimaryCareManager(cmd.orgId(), staffId);
            if (myTeams == null || myTeams.isEmpty()) {
                reply.append("\n你名下暂无主责健管组。可先打开患者手动分配，或到「健管组」创建以你为主责的小组后再改挂。");
                actions.add(AgentAction.navigate("去分配健管组", "join-care-team", peopleId));
            } else {
                reply.append("\n如需由你继续服务，可确认将患者改挂到你主责的健管组；不改组也可先打开患者查看。");
                for (CareTeam team : myTeams) {
                    if (team == null || !StringUtils.hasText(team.getId())) {
                        continue;
                    }
                    // 已在该组则不必再挂「改到本组」
                    if (team.getId().equals(item.getCareTeamId())) {
                        continue;
                    }
                    String label = myTeams.size() == 1
                            ? "确认改到我名下（" + nullToEmpty(team.getName()) + "）"
                            : "改到「" + nullToEmpty(team.getName()) + "」";
                    actions.add(AgentAction.changeCareTeam(label, peopleId, team.getId()));
                }
                if (actions.stream().noneMatch(a -> "CHANGE_CARE_TEAM".equals(a.path()))) {
                    // 人已在我的某一组但 primary 字段不一致等边缘情况
                    reply.append("\n患者已在你相关健管组内，可直接打开继续服务。");
                }
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

    private static String nullToEmpty(String s) {
        return StringUtils.hasText(s) ? s.trim() : "未命名组";
    }

    private AgentResponse doCreate(AgentSkillContext ctx, CreateArchiveDraft draft, String attachPeopleId) {
        AgentChatCommand cmd = ctx.command();
        String accountId = requireAccountId();
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
        String verb = attachPeopleId != null ? "已关联已有档案并加入本机构" : "已建档";
        List<AgentAction> actions = new ArrayList<>();
        actions.add(AgentAction.focusPatient("打开该患者", peopleId, false));
        String reply;
        // 新建档默认未入组：先分配健管组，再首诊（「我的患者」/待办归属都靠组）
        if (!StringUtils.hasText(item.getCareTeamId())) {
            reply = verb + "：「" + name + "」。下一步请先把患者分配到健管组（建议挂到你主责的组），"
                    + "再去做首诊评估与制定方案。";
            actions.add(AgentAction.navigate("分配健管组", "join-care-team", peopleId));
            actions.add(AgentAction.openSheet("稍后再做首诊", "intake", peopleId));
        } else {
            reply = verb + "：「" + name + "」。建议下一步：完成首诊评估，再制定管理方案。";
            actions.add(AgentAction.openSheet("去做首诊评估", "intake", peopleId));
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
            AgentSkillContext ctx, CreateArchiveDraft draft, List<DuplicateCandidate> needConfirm) {
        StringBuilder sb = new StringBuilder("发现可能的重复档案，请确认：\n");
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
                actions.add(AgentAction.focusPatient("打开「" + d.displayName() + "」", d.peopleId(), false));
            } else {
                actions.add(AgentAction.createArchive(
                        "就是「" + d.displayName() + "」，挂接到本机构", archivePayload(draft, d.peopleId())));
            }
        }
        actions.add(AgentAction.createArchive("不是同一人，新建档案", archivePayload(draft, null)));
        sb.append("请点下方按钮确认。");
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

    private AgentResponse needInfo(AgentSkillContext ctx, CreateArchiveDraft draft, String prompt) {
        StringBuilder status = new StringBuilder();
        status.append(draft.hasName() ? "姓名✓ " : "姓名✗ ");
        status.append(draft.hasGender() ? "性别✓ " : "性别✗ ");
        status.append(draft.hasBirthday() ? "生日✓ " : "生日✗ ");
        status.append(draft.hasMobile() ? "电话✓" : "电话✗");
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CREATE_ARCHIVE.name(),
                "CREATE_ARCHIVE_NEED_INFO",
                prompt + "\n当前：" + status,
                List.of(),
                List.of(),
                null);
    }

    private static String missingPrompt(CreateArchiveDraft draft) {
        List<String> miss = new ArrayList<>();
        if (!draft.hasName()) {
            miss.add("姓名");
        }
        if (!draft.hasGender()) {
            miss.add("性别（男/女）");
        }
        if (!draft.hasBirthday()) {
            miss.add("出生日期（如 1990-05-01）");
        }
        if (!draft.hasMobile()) {
            miss.add("本人手机号");
        }
        return "建档还需要：" + String.join("、", miss)
                + "。可直接回复，例如：李敏 女 1990-05-01 13800138000";
    }

    private static CreateArchiveDraft mergeFromHistory(List<ChatTurn> history, CreateArchiveDraft current) {
        CreateArchiveDraft merged = new CreateArchiveDraft(null, null, null, null, false);
        if (history != null) {
            for (ChatTurn turn : history) {
                if (turn == null) {
                    continue;
                }
                if (StringUtils.hasText(turn.user())) {
                    merged = CreateArchiveParser.merge(merged, CreateArchiveParser.parse(turn.user()));
                }
            }
        }
        return CreateArchiveParser.merge(merged, current);
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

    public static boolean matchesMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        if (containsAny(
                msg,
                "建档",
                "新建患者",
                "新建客户",
                "录入患者",
                "登记患者",
                "帮我建一个",
                "建一个患者",
                "建个患者",
                "create archive",
                "new patient")) {
            return true;
        }
        // 小白常在任意会话（含已选患者）直接粘贴四要素，不写「建档」
        return looksLikeArchiveDraft(message);
    }

    /**
     * 启发式：抽到较完整身份信息即当作建档意图。
     *
     * <p>避免误伤普通病情描述（仅姓名+性别、无手机/生日时不命中）。
     */
    static boolean looksLikeArchiveDraft(String message) {
        CreateArchiveDraft draft = CreateArchiveParser.parse(message);
        if (draft.isComplete()) {
            return true;
        }
        // 姓名 + 手机号：足够启动建档（缺项由 Skill 追问）
        if (draft.hasName() && draft.hasMobile()) {
            return true;
        }
        // 表单口吻：有「姓名」标签，且有手机号或生日
        String text = message.trim();
        boolean labeled = text.contains("姓名") || text.contains("患者姓名");
        if (labeled && draft.hasName() && (draft.hasMobile() || draft.hasBirthday())) {
            return true;
        }
        return false;
    }

    private static boolean containsAny(String msg, String... keys) {
        for (String k : keys) {
            if (msg.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
