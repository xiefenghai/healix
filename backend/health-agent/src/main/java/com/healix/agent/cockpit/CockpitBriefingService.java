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
import java.util.List;
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
        CockpitBriefingDto dto = tryLlm(summary, top);
        if (dto == null || !StringUtils.hasText(dto.getText())) {
            dto = templateBriefing(summary, top);
        }
        dto.setCached(false);
        dto.setActions(toActions(top));
        redisTemplate.opsForValue().set(cacheKey, JsonUtils.toJson(dto), CACHE_TTL);
        return dto;
    }

    private CockpitBriefingDto tryLlm(CockpitSummaryDto summary, List<CockpitPriorityCardDto> top) {
        if (!llmClient.isEnabled()) {
            return null;
        }
        try {
            String system =
                    """
                    你是 Healix-健管智能体小智，健管师机构工作台的开场简报助手。
                    用简洁中文（120–220 字）给出今日优先建议。
                    硬性规则：
                    1. 只能依据下方「摘要」与「优先名单」写，禁止编造名单外的患者、人数或分类标签。
                    2. 优先名单为空时：说明今日暂无待办/超期优先对象，引导查看左侧优先名单或工作台待办即可。
                    3. 若摘要中方案待确认或报告待审阅大于 0，用一句提醒健管师处理右栏草稿箱（勿虚构具体患者）。
                    4. 禁止使用「红人」「红标」「依从红人」「红灯」等说法；也不要用同义改写暗示这类标签。
                    5. 不做诊断、不开方；禁止 Markdown（不要 #、**、表格、列表符号），用纯中文短句。
                    """;
            String user = buildUserPrompt(summary, top);
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
            CockpitSummaryDto summary, List<CockpitPriorityCardDto> top) {
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
                sb.append(c.getDisplayName() == null ? "患者" : c.getDisplayName())
                        .append("（")
                        .append(c.getTopReason() == null ? "需关注" : c.getTopReason())
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

    private static String buildUserPrompt(CockpitSummaryDto summary, List<CockpitPriorityCardDto> top) {
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
                sb.append("- ")
                        .append(c.getDisplayName())
                        .append(" | ")
                        .append(c.getTopReason())
                        .append(" | badges=")
                        .append(c.getBadges())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static List<CockpitBriefingDto.BriefingAction> toActions(List<CockpitPriorityCardDto> top) {
        List<CockpitBriefingDto.BriefingAction> actions = new ArrayList<>();
        for (CockpitPriorityCardDto c : top) {
            if (actions.size() >= 3) {
                break;
            }
            CockpitBriefingDto.BriefingAction a = new CockpitBriefingDto.BriefingAction();
            a.setPeopleId(c.getPeopleId());
            a.setLabel("处理" + (c.getDisplayName() == null ? "该患者" : c.getDisplayName()));
            actions.add(a);
        }
        return actions;
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
        // v4：简报纳入方案/报告待确认计数
        return HealthConstants.REDIS_COCKPIT_BRIEFING_PREFIX + staffId + ":" + orgId + ":" + day + ":v4";
    }
}
