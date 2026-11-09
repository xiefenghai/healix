package com.healix.agent.cockpit;

import com.healix.agent.llm.LlmClient;
import com.healix.agent.support.AgentReplyPlainText;
import com.healix.common.constant.HealthConstants;
import com.healix.common.util.JsonUtils;
import com.healix.core.cockpit.dto.CockpitBriefingDto;
import com.healix.core.cockpit.dto.CockpitPriorityCardDto;
import com.healix.core.cockpit.dto.CockpitSummaryDto;
import com.healix.core.cockpit.service.CockpitService;
import com.healix.core.job.support.JobCronSupport;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 驾驶舱开场简报：优先 LLM，失败降级模板；同 staff+org 自然日缓存（G8）。
 *
 * <p>无待办时给欢迎/下一步短句；0 患者不提「左侧选人」，建档交给前端引导卡。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CockpitBriefingService {

    private static final Duration CACHE_TTL = Duration.ofHours(36);
    private static final int TOP_N = 5;
    /** 拉取近况事实的人数（每人几次查询，控制开销） */
    private static final int FACT_N = 3;

    private final CockpitService cockpitService;
    private final LlmClient llmClient;
    private final StringRedisTemplate redisTemplate;

    public CockpitBriefingDto briefing(String tenantId, String orgId, String staffId, boolean forceRefresh) {
        // 先取摘要：冷启动态依赖主责组数 / 在管人数，需写入缓存键，建组后不致仍命中「先建组」旧简报
        CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
        String cacheKey = cacheKey(
                staffId,
                orgId,
                summary.getPrimaryCareTeamCount(),
                summary.getOrgPatientCount(),
                summary.getUnassignedPatientCount());
        if (!forceRefresh) {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.hasText(cached)) {
                CockpitBriefingDto dto = JsonUtils.fromJson(cached, CockpitBriefingDto.class);
                if (dto != null && StringUtils.hasText(dto.getText())) {
                    dto.setCached(true);
                    return dto;
                }
            }
        }

        List<CockpitPriorityCardDto> top = cockpitService.topUrgent(tenantId, orgId, TOP_N);
        Map<String, List<String>> facts = new HashMap<>();
        for (int i = 0; i < Math.min(FACT_N, top.size()); i++) {
            String pid = top.get(i).getPeopleId();
            try {
                facts.put(pid, cockpitService.briefFacts(tenantId, orgId, pid));
            } catch (Exception e) {
                log.debug("briefing facts failed people={}", pid, e);
            }
        }

        CockpitBriefingDto dto;
        if (isIdleDay(summary, top)) {
            // 空态用可控模板：讲清下一步，避免 LLM 又说「去看左侧」
            dto = guidedIdleBriefing(summary);
            dto.setActions(idleActions(summary));
        } else {
            dto = tryLlm(summary, top, facts);
            if (dto == null || !StringUtils.hasText(dto.getText())) {
                dto = templateBriefing(summary, top, facts);
            }
            dto.setActions(enrichDailyActions(toActions(top), summary));
        }
        dto.setCached(false);
        redisTemplate.opsForValue().set(cacheKey, JsonUtils.toJson(dto), CACHE_TTL);
        return dto;
    }

    private static boolean isIdleDay(CockpitSummaryDto summary, List<CockpitPriorityCardDto> top) {
        return top.isEmpty()
                && summary.getOpenTaskCount() <= 0
                && summary.getOverdueCount() <= 0
                && summary.getPendingCarePlanDraftCount() <= 0
                && summary.getPendingReportDraftCount() <= 0;
    }

    private static String dayPartHello() {
        int h = LocalTime.now(JobCronSupport.ZONE).getHour();
        if (h < 11) {
            return "早上好";
        }
        if (h < 13) {
            return "中午好";
        }
        if (h < 18) {
            return "下午好";
        }
        return "晚上好";
    }

    private static CockpitBriefingDto guidedIdleBriefing(CockpitSummaryDto summary) {
        boolean noPrimaryTeam = summary.getPrimaryCareTeamCount() <= 0;
        boolean coldStart = summary.getOrgPatientCount() <= 0;
        int unassigned = Math.max(0, summary.getUnassignedPatientCount());
        String hello = dayPartHello();
        // 短句 + 下一步；0 患者绝不提「去左侧选人」
        String text;
        if (noPrimaryTeam) {
            text = "欢迎使用智能驾驶舱。开始服务前，请先创建以你为主责的健管组"
                    + "（「我的患者」和待办都按主责组分）。点下方按钮即可，建好组后再建档。";
        } else if (coldStart) {
            text = "欢迎使用智能驾驶舱！今天还没有待办。"
                    + "请点下方卡片开始第一位客户建档；"
                    + "若已有客户名单，也可直接发给我（如：张三 女 45岁 13800000000）。";
        } else if (unassigned > 0) {
            text = hello
                    + "。目前有 "
                    + unassigned
                    + " 位公共患者待认领。您可以先把他们认领到您的组，或直接新建患者。";
        } else {
            text = hello + "。今天没有紧急待办。需要跟进时，可从左侧「我的患者」选人。";
        }
        CockpitBriefingDto dto = new CockpitBriefingDto();
        dto.setText(text);
        dto.setFromLlm(false);
        dto.setNote(
                noPrimaryTeam
                        ? "冷启动-先建健管组"
                        : coldStart
                                ? "冷启动-建档"
                                : unassigned > 0 ? "清闲日-公共池提醒" : "清闲日引导简报");
        return dto;
    }

    /**
     * 空态简报动作：有公共池时挂认领 + 建档；前端还会追加「刷新今日建议」。
     */
    private static List<CockpitBriefingDto.BriefingAction> idleActions(CockpitSummaryDto summary) {
        List<CockpitBriefingDto.BriefingAction> out = new ArrayList<>();
        if (summary.getPrimaryCareTeamCount() <= 0) {
            CockpitBriefingDto.BriefingAction createTeam = new CockpitBriefingDto.BriefingAction();
            createTeam.setType("NAVIGATE");
            // 驾驶舱内打开创建抽屉（非跳转健管组页）
            createTeam.setPath("create-care-team");
            createTeam.setLabel("先创建我的健管组");
            out.add(createTeam);
            return out;
        }
        int unassigned = Math.max(0, summary.getUnassignedPatientCount());
        if (unassigned > 0) {
            CockpitBriefingDto.BriefingAction claim = new CockpitBriefingDto.BriefingAction();
            claim.setType("NAVIGATE");
            claim.setPath("unassigned-workbench");
            claim.setLabel("去认领/分配患者（" + unassigned + "）");
            out.add(claim);
            CockpitBriefingDto.BriefingAction archive = new CockpitBriefingDto.BriefingAction();
            archive.setType("NAVIGATE");
            archive.setPath("create-archive");
            archive.setLabel("新建患者档案");
            out.add(archive);
            return out;
        }
        if (summary.getOrgPatientCount() > 0) {
            CockpitBriefingDto.BriefingAction mine = new CockpitBriefingDto.BriefingAction();
            mine.setType("SET_COCKPIT_TAB");
            mine.setPath("mine");
            mine.setLabel("查看我的患者");
            out.add(mine);
        }
        return out;
    }

    private CockpitBriefingDto tryLlm(
            CockpitSummaryDto summary, List<CockpitPriorityCardDto> top, Map<String, List<String>> facts) {
        if (!llmClient.isEnabled()) {
            return null;
        }
        try {
            String system =
                    """
                    你是 Healix-健管智能体小智，健管师机构工作台的开场简报助手。
                    用简洁中文（120–260 字）给出今日优先建议：先说今天建议先处理哪几位，再逐人用一句话说明理由。
                    硬性规则：
                    1. 只能依据下方「摘要」与「优先名单」写，禁止编造名单外的患者、人数或分类标签。
                       理由优先引用名单里给出的具体读数、连续天数（如「今早血压 162/98」「服药连续 3 天未完成」），不得自行编造数值。
                    2. 优先名单为空时：用一两句说清「现在立刻该做什么」，不要背诵整段服务流程说明书。
                       若摘要显示主责健管组数为 0：只建议先创建以自己为主责的健管组（点下方按钮打开创建抽屉即可），
                       严禁说「去系统管理」「没有权限」「联系管理员」；
                       已有主责组但无患者：只建议建档或把客户信息发给助手，禁止说「去左侧选患者」；
                       若摘要「未分组患者」大于 0：称其为「公共患者待认领」，提醒可点下方「去认领/分配患者」认领到自己的组；
                       不要只说「去看左侧橙色条」而无动作指引；禁止装作没事人只说「今日暂无待办」；
                       有患者则建议从「我的患者」选人继续，勿暗示今日优先名单一定有人。
                    3. 若摘要中方案待确认或报告待审阅大于 0，用一句提醒健管师处理右栏草稿箱（勿虚构具体患者）。
                    4. 不要使用「红人」「红标」「依从红人」「红灯患者」等产品未展示的标签；优先用名单/摘要里的具体事实：连续未打卡、近7日依从偏低、超期待办、临床高/中风险、血压读数等。
                    5. 不做诊断、不开方；禁止 Markdown（不要 #、**、表格、列表符号），用纯中文短句。
                    6. 标准服务路径固定为：建档→分组→首诊评估→制定方案→日常跟进→定期报告→阶段总结→结束；
                       不要缩短、改写或调换这些环节名称；禁止跳步建议。
                    7. 若名单主因/待办含「分组」「分配健管组」「TEAM_ASSIGN」：先建议完成分组；
                       若含「首诊」「ONBOARDING」：建议完成首诊评估。
                       禁止对首诊待办再说「开随访」「新建随访」——首诊本身已是待办，再开随访会叠单。
                    8. 若摘要「超期」大于 0：开场用「您有 X 项逾期待办」点明；若同时有公共患者待认领，接上人数并问是否先处理。
                    9. 若优先名单里有人已入组、且首诊/ONBOARDING 已超期：点名该患者说明首诊已逾期；
                       禁止只谈分组/认领而漏掉逾期首诊。
                    """;
            String user = buildUserPrompt(summary, top, facts);
            var llm = llmClient.chat("COCKPIT_BRIEFING", system, user, List.of());
            if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
                return null;
            }
            String text = AgentReplyPlainText.sanitize(llm.content());
            if (mentionsForbiddenRedLabel(text)) {
                log.warn("cockpit briefing rejected: forbidden red-label wording");
                return null;
            }
            CockpitBriefingDto dto = new CockpitBriefingDto();
            dto.setText(text);
            dto.setFromLlm(true);
            return dto;
        } catch (Exception e) {
            log.warn("cockpit briefing LLM failed: {}", e.getMessage());
            return null;
        }
    }

    private static CockpitBriefingDto templateBriefing(
            CockpitSummaryDto summary, List<CockpitPriorityCardDto> top, Map<String, List<String>> facts) {
        StringBuilder sb = new StringBuilder();
        int overdue = Math.max(0, summary.getOverdueCount());
        int unassigned = Math.max(0, summary.getUnassignedPatientCount());
        sb.append(dayPartHello()).append("。");
        if (overdue > 0 && unassigned > 0) {
            sb.append("您有 ")
                    .append(overdue)
                    .append(" 项逾期待办，另有 ")
                    .append(unassigned)
                    .append(" 位公共患者待认领，需要先处理吗？");
        } else if (overdue > 0) {
            sb.append("您有 ").append(overdue).append(" 项逾期待办，需要先处理吗？");
        } else {
            String overdueLead = overdueIntakeReminder(top);
            if (StringUtils.hasText(overdueLead)) {
                sb.append(overdueLead);
            }
            sb.append("今日待办 ").append(summary.getOpenTaskCount()).append("。");
            if (unassigned > 0) {
                sb.append("另有 ").append(unassigned).append(" 位公共患者待认领。");
            }
        }
        if (top.isEmpty()) {
            if (summary.getOrgPatientCount() <= 0) {
                sb.append("机构里还没有客户档案。请点下方卡片建档，或直接把客户信息发给我。");
            } else if (unassigned > 0 && overdue <= 0) {
                sb.append("可点下方按钮认领到您的组，或新建患者。");
            } else if (overdue <= 0) {
                sb.append("当前没有紧急优先对象。可从左侧「我的患者」继续跟进。");
            }
        } else {
            sb.append("建议优先：");
            int n = Math.min(3, top.size());
            for (int i = 0; i < n; i++) {
                CockpitPriorityCardDto c = top.get(i);
                if (i > 0) {
                    sb.append("；");
                }
                List<String> why = new ArrayList<>(facts.getOrDefault(c.getPeopleId(), List.of()));
                if (why.isEmpty()) {
                    why.add(c.getTopReason() == null ? "需关注" : c.getTopReason());
                }
                sb.append(c.getDisplayName() == null ? "患者" : c.getDisplayName())
                        .append("（")
                        .append(String.join("，", why.subList(0, Math.min(2, why.size()))))
                        .append("）");
            }
            sb.append("。点左侧名单即可带入对话处理。");
        }
        int planDrafts = summary.getPendingCarePlanDraftCount();
        int reportDrafts = summary.getPendingReportDraftCount();
        if (planDrafts + reportDrafts > 0) {
            sb.append("另有待你确认：");
            List<String> parts = new ArrayList<>();
            if (planDrafts > 0) {
                parts.add("方案相关 " + planDrafts + " 项");
            }
            if (reportDrafts > 0) {
                parts.add("报告审阅 " + reportDrafts + " 项");
            }
            sb.append(String.join("、", parts)).append("，选中患者后可在右栏草稿箱处理。");
        }
        CockpitBriefingDto dto = new CockpitBriefingDto();
        dto.setText(sb.toString());
        dto.setFromLlm(false);
        dto.setNote("模板简报（LLM 不可用或失败时降级）");
        return dto;
    }

    private static String buildUserPrompt(
            CockpitSummaryDto summary, List<CockpitPriorityCardDto> top, Map<String, List<String>> facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("摘要：待办=")
                .append(summary.getOpenTaskCount())
                .append(" 超期=")
                .append(summary.getOverdueCount())
                .append(" 在管患者=")
                .append(summary.getOrgPatientCount())
                .append(" 未分组患者=")
                .append(summary.getUnassignedPatientCount())
                .append(" 主责健管组=")
                .append(summary.getPrimaryCareTeamCount())
                .append(" 方案待确认=")
                .append(summary.getPendingCarePlanDraftCount())
                .append(" 报告待审阅=")
                .append(summary.getPendingReportDraftCount())
                .append('\n');
        sb.append("优先名单：\n");
        if (top.isEmpty()) {
            sb.append("（空）\n");
        } else {
            for (CockpitPriorityCardDto c : top) {
                sb.append("- ").append(c.getDisplayName());
                if (c.getAge() != null) {
                    sb.append(' ').append(c.getAge()).append("岁");
                }
                if (c.getDiseaseLabels() != null && !c.getDiseaseLabels().isEmpty()) {
                    sb.append(' ').append(String.join("/", c.getDiseaseLabels()));
                }
                sb.append(" | 主因=").append(c.getTopReason());
                if (c.getReasonDetails() != null && !c.getReasonDetails().isEmpty()) {
                    sb.append(" | 待办=").append(String.join("；", c.getReasonDetails()));
                }
                List<String> f = facts.get(c.getPeopleId());
                if (f != null && !f.isEmpty()) {
                    sb.append(" | 近况=").append(String.join("；", f));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 前 3 位：每人只挂一个直接办理动作（点了才执行）；无匹配待办时回落「处理某某」点人。 */
    private static List<CockpitBriefingDto.BriefingAction> toActions(List<CockpitPriorityCardDto> top) {
        List<CockpitBriefingDto.BriefingAction> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            CockpitPriorityCardDto c = top.get(i);
            String name = c.getDisplayName() == null ? "该患者" : c.getDisplayName();
            CockpitBriefingDto.BriefingAction d = doAction(c, name);
            if (d != null) {
                out.add(d);
                continue;
            }
            CockpitBriefingDto.BriefingAction a = new CockpitBriefingDto.BriefingAction();
            a.setPeopleId(c.getPeopleId());
            a.setLabel("处理" + name);
            out.add(a);
        }
        return out;
    }

    /** 日常简报：逾期待办 / 公共池认领先于具体患者动作。 */
    private static List<CockpitBriefingDto.BriefingAction> enrichDailyActions(
            List<CockpitBriefingDto.BriefingAction> base, CockpitSummaryDto summary) {
        List<CockpitBriefingDto.BriefingAction> out = new ArrayList<>();
        int overdue = Math.max(0, summary.getOverdueCount());
        int unassigned = Math.max(0, summary.getUnassignedPatientCount());
        if (overdue > 0) {
            CockpitBriefingDto.BriefingAction overdueAct = new CockpitBriefingDto.BriefingAction();
            overdueAct.setType("SET_COCKPIT_TAB");
            overdueAct.setPath("urgent");
            overdueAct.setLabel("查看逾期待办（" + overdue + "）");
            out.add(overdueAct);
        }
        if (unassigned > 0) {
            CockpitBriefingDto.BriefingAction claim = new CockpitBriefingDto.BriefingAction();
            claim.setType("NAVIGATE");
            claim.setPath("unassigned-workbench");
            claim.setLabel("去认领/分配患者（" + unassigned + "）");
            out.add(claim);
        }
        if (base != null) {
            out.addAll(base);
        }
        if (out.size() > 5) {
            return new ArrayList<>(out.subList(0, 5));
        }
        return out;
    }

    private static CockpitBriefingDto.BriefingAction doAction(CockpitPriorityCardDto c, String name) {
        List<String> types = c.getTaskTypes() == null ? List.of() : c.getTaskTypes();
        List<String> badges = c.getBadges() == null ? List.of() : c.getBadges();
        List<String> labels = c.getTaskTypeLabels() == null ? List.of() : c.getTaskTypeLabels();
        CockpitBriefingDto.BriefingAction a = new CockpitBriefingDto.BriefingAction();
        a.setPeopleId(c.getPeopleId());

        // 标准路径：分组优先于首诊评估；首诊待办也是 FOLLOW_UP，绝不能再「开随访」叠单
        if (types.contains("TEAM_ASSIGN") || badges.contains("TEAM_ASSIGN")) {
            a.setType("NAVIGATE");
            a.setPath("join-care-team");
            a.setLabel("给" + name + "分组");
            return a;
        }
        boolean onboarding = badges.contains("ONBOARDING")
                || labels.stream().anyMatch(l -> l != null && (l.contains("首诊") || l.contains("入组")));
        if (onboarding) {
            a.setType("OPEN_SHEET");
            a.setPath("intake");
            a.setLabel("去做" + name + "的首诊评估");
            return a;
        }
        if (types.contains("PLAN_CREATE") || types.contains("PLAN_REVIEW")) {
            a.setType("TRIGGER_CAPABILITY");
            a.setPath("CARE_PLAN");
            a.setLabel("生成" + name + "的方案草稿");
            return a;
        }
        if (types.contains("REPORT_REVIEW")) {
            a.setType("TRIGGER_CAPABILITY");
            a.setPath("REPORT_SUMMARY");
            a.setLabel("生成" + name + "的报告点评");
            return a;
        }
        // 已有随访/打卡待办：带入对话处理，不要再新建一张随访
        if (types.contains("FOLLOW_UP") || types.contains("PLAN_NUDGE")) {
            a.setType("FOCUS_PATIENT");
            a.setLabel("处理" + name + "的待办");
            return a;
        }
        if (types.contains("METRIC_ALERT")) {
            a.setType("OPEN_SHEET");
            a.setPath("observations");
            a.setLabel("查看" + name + "的指标");
            return a;
        }
        // 患者主动申请回访：可新建常规随访
        if (badges.contains("PATIENT_REQUEST")) {
            a.setType("CALL_API");
            a.setPath("CREATE_FOLLOWUP");
            a.setLabel("给" + name + "开随访");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("followupType", "ROUTINE");
            payload.put("createTask", true);
            payload.put("completeNow", false);
            a.setPayload(payload);
            return a;
        }
        return null;
    }

    /** 已入组且首诊逾期：开场先点名，再谈其它待办。未分组不计入。 */
    private static String overdueIntakeReminder(List<CockpitPriorityCardDto> top) {
        String name = null;
        for (CockpitPriorityCardDto c : top) {
            if (!isAssignedOverdueIntake(c)) {
                continue;
            }
            String display = c.getDisplayName() == null ? "" : c.getDisplayName().trim();
            if (!StringUtils.hasText(display)) {
                continue;
            }
            name = display;
            break;
        }
        if (!StringUtils.hasText(name)) {
            return "";
        }
        return "提醒您，【" + name + "】等患者的首诊评估已逾期，建议优先处理。";
    }

    private static boolean isAssignedOverdueIntake(CockpitPriorityCardDto c) {
        if (c == null || !StringUtils.hasText(c.getCareTeamId())) {
            return false;
        }
        List<String> badges = c.getBadges() == null ? List.of() : c.getBadges();
        List<String> labels = c.getTaskTypeLabels() == null ? List.of() : c.getTaskTypeLabels();
        boolean intake = badges.contains("ONBOARDING")
                || labels.stream().anyMatch(l -> l != null && l.contains("首诊"));
        boolean overdue = badges.contains("OVERDUE") || c.getOverdueTaskCount() > 0;
        return intake && overdue;
    }

    /** 简报仍拦截旧「红人」标签词：命中则丢弃 LLM 结果，走模板降级。 */
    private static boolean mentionsForbiddenRedLabel(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("红人")
                || text.contains("红标")
                || text.contains("依从红")
                || text.contains("红灯患者");
    }

    private static String cacheKey(
            String staffId, String orgId, int primaryTeamCount, int orgPatientCount, int unassignedCount) {
        LocalDate day = LocalDate.now(JobCronSupport.ZONE);
        // v18：日常简报强调逾期待办 + 公共池认领 CTA
        return HealthConstants.REDIS_COCKPIT_BRIEFING_PREFIX
                + staffId
                + ":"
                + orgId
                + ":"
                + day
                + ":v18:t"
                + primaryTeamCount
                + ":p"
                + orgPatientCount
                + ":u"
                + unassignedCount;
    }
}
