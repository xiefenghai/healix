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
        String cacheKey = cacheKey(staffId, orgId);
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

        CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
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
        CockpitBriefingDto dto = tryLlm(summary, top, facts);
        if (dto == null || !StringUtils.hasText(dto.getText())) {
            dto = templateBriefing(summary, top, facts);
        }
        dto.setCached(false);
        dto.setActions(toActions(top));
        redisTemplate.opsForValue().set(cacheKey, JsonUtils.toJson(dto), CACHE_TTL);
        return dto;
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
                    2. 优先名单为空时：说明今日暂无待办/超期优先对象，引导查看左侧优先名单或工作台待办即可。
                    3. 若摘要中方案待确认或报告待审阅大于 0，用一句提醒健管师处理右栏草稿箱（勿虚构具体患者）。
                    4. 禁止使用「红人」「红标」「依从红人」「红灯」等说法；也不要用同义改写暗示这类标签。
                    5. 不做诊断、不开方；禁止 Markdown（不要 #、**、表格、列表符号），用纯中文短句。
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
            sb.append("当前没有紧急优先对象，可先处理工作台公共池，或查看左侧优先名单。");
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

    /** 前 3 位：每人一个「处理某某」，再按其待办挂一个直接办理动作（点了才执行）。 */
    private static List<CockpitBriefingDto.BriefingAction> toActions(List<CockpitPriorityCardDto> top) {
        List<CockpitBriefingDto.BriefingAction> focus = new ArrayList<>();
        List<CockpitBriefingDto.BriefingAction> doIt = new ArrayList<>();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            CockpitPriorityCardDto c = top.get(i);
            String name = c.getDisplayName() == null ? "该患者" : c.getDisplayName();
            CockpitBriefingDto.BriefingAction a = new CockpitBriefingDto.BriefingAction();
            a.setPeopleId(c.getPeopleId());
            a.setLabel("处理" + name);
            focus.add(a);
            CockpitBriefingDto.BriefingAction d = doAction(c, name);
            if (d != null) {
                doIt.add(d);
            }
        }
        List<CockpitBriefingDto.BriefingAction> out = new ArrayList<>(focus);
        out.addAll(doIt);
        return out;
    }

    private static CockpitBriefingDto.BriefingAction doAction(CockpitPriorityCardDto c, String name) {
        List<String> types = c.getTaskTypes() == null ? List.of() : c.getTaskTypes();
        List<String> badges = c.getBadges() == null ? List.of() : c.getBadges();
        CockpitBriefingDto.BriefingAction a = new CockpitBriefingDto.BriefingAction();
        a.setPeopleId(c.getPeopleId());
        if (badges.contains("PATIENT_REQUEST")
                || types.contains("METRIC_ALERT")
                || types.contains("FOLLOW_UP")
                || types.contains("PLAN_NUDGE")) {
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

    private static String cacheKey(String staffId, String orgId) {
        LocalDate day = LocalDate.now(JobCronSupport.ZONE);
        // v5：简报带具体读数与直接办理动作
        return HealthConstants.REDIS_COCKPIT_BRIEFING_PREFIX + staffId + ":" + orgId + ":" + day + ":v5";
    }
}
