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
 * <p>无待办时不改服务流程，只讲清标准链路并给出下一步可点动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CockpitBriefingService {

    private static final Duration CACHE_TTL = Duration.ofHours(36);
    private static final int TOP_N = 5;
    /** 拉取近况事实的人数（每人几次查询，控制开销） */
    private static final int FACT_N = 3;

    /** 健管服务标准流程（开场引导口径，勿擅自缩短） */
    private static final String SERVICE_FLOW =
            "建档 → 首诊评估 → 制定方案 → 持续跟进随访 → 周报 → 阶段评估报告 → 服务结束";

    private final CockpitService cockpitService;
    private final LlmClient llmClient;
    private final StringRedisTemplate redisTemplate;

    public CockpitBriefingDto briefing(String tenantId, String orgId, String staffId, boolean forceRefresh) {
        // 先取摘要：冷启动态依赖主责组数 / 在管人数，需写入缓存键，建组后不致仍命中「先建组」旧简报
        CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
        String cacheKey = cacheKey(
                staffId, orgId, summary.getPrimaryCareTeamCount(), summary.getOrgPatientCount());
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
            // 空态用可控模板：讲清服务流程 + 下一步，避免 LLM 又说「去看左侧」
            dto = guidedIdleBriefing(summary);
            dto.setActions(idleActions(summary));
        } else {
            dto = tryLlm(summary, top, facts);
            if (dto == null || !StringUtils.hasText(dto.getText())) {
                dto = templateBriefing(summary, top, facts);
            }
            dto.setActions(toActions(top));
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

    private static CockpitBriefingDto guidedIdleBriefing(CockpitSummaryDto summary) {
        boolean noPrimaryTeam = summary.getPrimaryCareTeamCount() <= 0;
        boolean coldStart = summary.getOrgPatientCount() <= 0;
        StringBuilder sb = new StringBuilder();
        if (noPrimaryTeam) {
            sb.append("欢迎使用健管驾驶舱。服务患者前，请先创建以你为主责的健管组：")
                    .append("「我的患者」、待办归属都按健管组主责划分。")
                    .append("点下方「先创建我的健管组」，选自己为主责即可。")
                    .append("建组后再建档入组，标准服务流程是：")
                    .append(SERVICE_FLOW)
                    .append("。");
        } else if (coldStart) {
            sb.append("健管组已就绪。标准服务流程是：")
                    .append(SERVICE_FLOW)
                    .append("。当前还没有在管患者，建议下一步「建档」：点下方「开始建档」，")
                    .append("或直接对我说「建档 姓名 性别 生日 手机号」。建档后记得加入你的健管组，再依次做首诊评估与制定方案。");
        } else {
            sb.append("今日暂无紧急待办。服务流程提醒：")
                    .append(SERVICE_FLOW)
                    .append("。可从左侧「我的患者」选人，继续其当前环节（首诊、方案、随访或报告）；")
                    .append("也可为新客户建档，开启一轮新的服务。");
        }
        CockpitBriefingDto dto = new CockpitBriefingDto();
        dto.setText(sb.toString());
        dto.setFromLlm(false);
        dto.setNote(noPrimaryTeam ? "冷启动-先建健管组" : coldStart ? "冷启动-建档" : "清闲日引导简报");
        return dto;
    }

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
        CockpitBriefingDto.BriefingAction archive = new CockpitBriefingDto.BriefingAction();
        archive.setType("TRIGGER_CAPABILITY");
        archive.setPath("CREATE_ARCHIVE");
        archive.setLabel(summary.getOrgPatientCount() <= 0 ? "开始建档（下一步）" : "为新客户建档");
        out.add(archive);
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
                    2. 优先名单为空时：不要空转说「去看左侧」；应提醒标准服务流程
                       （建档→首诊评估→制定方案→持续跟进随访→周报→阶段评估报告→服务结束）。
                       若摘要显示主责健管组数为 0：优先建议先创建以自己为主责的健管组；
                       已有主责组但无患者：建议建档；有患者则从「我的患者」选人继续当前环节。
                    3. 若摘要中方案待确认或报告待审阅大于 0，用一句提醒健管师处理右栏草稿箱（勿虚构具体患者）。
                    4. 禁止使用「红人」「红标」「依从红人」「红灯」等说法；也不要用同义改写暗示这类标签。
                    5. 不做诊断、不开方；禁止 Markdown（不要 #、**、表格、列表符号），用纯中文短句。
                    6. 不要缩短或改写服务流程环节名称。
                    7. 若名单主因/待办含「首诊」「入组」「ONBOARDING」「分配健管组」：必须建议完成首诊评估或分配健管组，
                       禁止说「开随访」「新建随访」——入组/首诊本身已是随访待办，再开随访会叠单。
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
        sb.append("今日待办 ")
                .append(summary.getOpenTaskCount())
                .append("，超期 ")
                .append(summary.getOverdueCount())
                .append("。");
        if (top.isEmpty()) {
            sb.append("当前没有紧急优先对象。服务流程：")
                    .append(SERVICE_FLOW)
                    .append("。可从左侧「我的患者」继续跟进，或为新客户建档。");
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

    private static CockpitBriefingDto.BriefingAction doAction(CockpitPriorityCardDto c, String name) {
        List<String> types = c.getTaskTypes() == null ? List.of() : c.getTaskTypes();
        List<String> badges = c.getBadges() == null ? List.of() : c.getBadges();
        List<String> labels = c.getTaskTypeLabels() == null ? List.of() : c.getTaskTypeLabels();
        CockpitBriefingDto.BriefingAction a = new CockpitBriefingDto.BriefingAction();
        a.setPeopleId(c.getPeopleId());

        // 入组/首诊待办也是 FOLLOW_UP 类型，绝不能再「开随访」叠单
        boolean onboarding = badges.contains("ONBOARDING")
                || labels.stream().anyMatch(l -> l != null && (l.contains("首诊") || l.contains("入组")));
        if (onboarding) {
            a.setType("OPEN_SHEET");
            a.setPath("intake");
            a.setLabel("去做" + name + "的首诊评估");
            return a;
        }
        if (types.contains("TEAM_ASSIGN") || badges.contains("TEAM_ASSIGN")) {
            a.setType("NAVIGATE");
            a.setPath("join-care-team");
            a.setLabel("给" + name + "分配健管组");
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

    /** 简报/对话暂不使用「红人」口径：命中则丢弃 LLM 结果，走模板降级。 */
    private static boolean mentionsForbiddenRedLabel(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("红人")
                || text.contains("红标")
                || text.contains("依从红")
                || text.contains("红灯患者");
    }

    private static String cacheKey(String staffId, String orgId, int primaryTeamCount, int orgPatientCount) {
        LocalDate day = LocalDate.now(JobCronSupport.ZONE);
        // v10：入组/首诊不再误挂「开随访」；键含组数/患者数
        return HealthConstants.REDIS_COCKPIT_BRIEFING_PREFIX
                + staffId
                + ":"
                + orgId
                + ":"
                + day
                + ":v10:t"
                + primaryTeamCount
                + ":p"
                + orgPatientCount;
    }
}
