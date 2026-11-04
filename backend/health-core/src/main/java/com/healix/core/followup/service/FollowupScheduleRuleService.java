package com.healix.core.followup.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.followup.domain.FollowupScheduleRule;
import com.healix.core.followup.mapper.FollowupScheduleRuleMapper;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.people.support.DiseaseCodeLabels;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 定期随访排期规则：租户管理员按「机构 × 病种」配置间隔，扫描时按就近原则解析。
 *
 * <p>优先级：机构+病种 &gt; 机构通用 &gt; 租户+病种 &gt; 租户通用 &gt; 系统默认。患者有多个病种档案时，
 * 每个病种各自解析，取启用规则中最短的间隔；全部命中「停用」时不自动排期。
 */
@Service
@RequiredArgsConstructor
public class FollowupScheduleRuleService {

    public static final int DEFAULT_INTERVAL_WITH_PLAN = 30;
    public static final int DEFAULT_INTERVAL_NO_PLAN = 90;
    static final int MIN_INTERVAL = 7;
    static final int MAX_INTERVAL = 365;

    private final FollowupScheduleRuleMapper ruleMapper;
    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final OrganizationMapper organizationMapper;

    /** 一次扫描内复用的租户规则快照，避免逐患者查库。 */
    public RuleSet loadRuleSet(String tenantId) {
        return new RuleSet(ruleMapper.listByTenant(tenantId));
    }

    /**
     * @return 间隔天数；null 表示命中停用规则，不自动排期
     */
    public Integer resolveIntervalDays(RuleSet rules, String tenantId, String orgId, String peopleId, boolean hasActivePlan) {
        List<String> diseases = diseaseCodes(tenantId, peopleId);
        if (diseases.isEmpty()) {
            return intervalOrNull(rules.match(orgId, ""), hasActivePlan);
        }
        Integer best = null;
        boolean anyEnabled = false;
        for (String code : diseases) {
            FollowupScheduleRule rule = rules.match(orgId, code);
            if (rule != null && !isEnabled(rule)) {
                continue;
            }
            anyEnabled = true;
            int days = intervalOf(rule, hasActivePlan);
            best = best == null ? days : Math.min(best, days);
        }
        return anyEnabled ? best : null;
    }

    public List<RuleView> list(String tenantId) {
        Map<String, String> orgNames = new HashMap<>();
        for (Organization org : organizationMapper.listByTenant(tenantId)) {
            orgNames.put(org.getId(), org.getName());
        }
        List<RuleView> out = new ArrayList<>();
        for (FollowupScheduleRule r : ruleMapper.listByTenant(tenantId)) {
            out.add(toView(r, orgNames));
        }
        return out;
    }

    public RuleView save(String tenantId, String staffId, SaveCommand cmd) {
        String orgId = normalize(cmd.orgId());
        String diseaseCode = normalize(cmd.diseaseCode()).toLowerCase(Locale.ROOT);
        Organization org = null;
        if (!orgId.isEmpty()) {
            org = organizationMapper.findById(orgId);
            if (org == null || !Objects.equals(org.getTenantId(), tenantId)) {
                throw new BusinessException(400, "机构不存在");
            }
        }
        boolean enabled = cmd.enabled() == null || cmd.enabled();
        int withPlan = enabled ? requireInterval(cmd.intervalWithPlan(), "有方案") : orDefault(cmd.intervalWithPlan(), DEFAULT_INTERVAL_WITH_PLAN);
        int noPlan = enabled ? requireInterval(cmd.intervalNoPlan(), "无方案") : orDefault(cmd.intervalNoPlan(), DEFAULT_INTERVAL_NO_PLAN);

        FollowupScheduleRule row;
        if (StringUtils.hasText(cmd.id())) {
            row = ruleMapper.findById(cmd.id());
            if (row == null || !Objects.equals(row.getTenantId(), tenantId)) {
                throw new BusinessException(404, "规则不存在");
            }
            if (!row.getOrgId().equals(orgId) || !row.getDiseaseCode().equals(diseaseCode)) {
                throw new BusinessException(400, "适用范围不可修改，请删除后重新添加");
            }
        } else {
            if (ruleMapper.findByScope(tenantId, orgId, diseaseCode) != null) {
                throw new BusinessException(400, "该范围已有规则，请直接编辑");
            }
            row = new FollowupScheduleRule();
            row.setTenantId(tenantId);
            row.setOrgId(orgId);
            row.setDiseaseCode(diseaseCode);
        }
        row.setIntervalWithPlan(withPlan);
        row.setIntervalNoPlan(noPlan);
        row.setEnabled(enabled ? 1 : 0);
        row.setUpdatedByStaffId(staffId);
        if (row.getPkId() == null) {
            EntityMeta.onCreate(row);
            try {
                ruleMapper.insert(row);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(400, "该范围已有规则，请直接编辑");
            }
        } else {
            row.setGmtModified(LocalDateTime.now());
            ruleMapper.update(row);
        }
        Map<String, String> orgNames = new HashMap<>();
        if (org != null) {
            orgNames.put(org.getId(), org.getName());
        }
        return toView(row, orgNames);
    }

    public void delete(String tenantId, String id) {
        FollowupScheduleRule row = ruleMapper.findById(id);
        if (row == null || !Objects.equals(row.getTenantId(), tenantId)) {
            throw new BusinessException(404, "规则不存在");
        }
        ruleMapper.softDelete(id);
    }

    private List<String> diseaseCodes(String tenantId, String peopleId) {
        Set<String> codes = new LinkedHashSet<>();
        for (PeopleDiseaseArchive a : diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId)) {
            if (StringUtils.hasText(a.getDiseaseCode())) {
                codes.add(a.getDiseaseCode().trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(codes);
    }

    private static int intervalOf(FollowupScheduleRule rule, boolean hasActivePlan) {
        if (rule == null) {
            return hasActivePlan ? DEFAULT_INTERVAL_WITH_PLAN : DEFAULT_INTERVAL_NO_PLAN;
        }
        return hasActivePlan ? rule.getIntervalWithPlan() : rule.getIntervalNoPlan();
    }

    private static Integer intervalOrNull(FollowupScheduleRule rule, boolean hasActivePlan) {
        if (rule != null && !isEnabled(rule)) {
            return null;
        }
        return intervalOf(rule, hasActivePlan);
    }

    private static boolean isEnabled(FollowupScheduleRule rule) {
        return rule.getEnabled() == null || rule.getEnabled() != 0;
    }

    private static int requireInterval(Integer days, String label) {
        if (days == null || days < MIN_INTERVAL || days > MAX_INTERVAL) {
            throw new BusinessException(400, label + "间隔需在 " + MIN_INTERVAL + "～" + MAX_INTERVAL + " 天之间");
        }
        return days;
    }

    private static int orDefault(Integer days, int fallback) {
        return days == null || days < MIN_INTERVAL || days > MAX_INTERVAL ? fallback : days;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private static RuleView toView(FollowupScheduleRule r, Map<String, String> orgNames) {
        return new RuleView(
                r.getId(),
                r.getOrgId(),
                r.getOrgId().isEmpty() ? "全部机构" : orgNames.getOrDefault(r.getOrgId(), "（已移除机构）"),
                r.getDiseaseCode(),
                r.getDiseaseCode().isEmpty() ? "不限病种" : DiseaseCodeLabels.label(r.getDiseaseCode()),
                r.getIntervalWithPlan(),
                r.getIntervalNoPlan(),
                isEnabled(r),
                r.getGmtModified());
    }

    public static final class RuleSet {
        private final Map<String, FollowupScheduleRule> byScope = new HashMap<>();

        RuleSet(List<FollowupScheduleRule> rules) {
            for (FollowupScheduleRule r : rules) {
                byScope.put(key(r.getOrgId(), r.getDiseaseCode()), r);
            }
        }

        /** 按优先级返回最具体的规则；均未配置时返回 null（走系统默认）。 */
        FollowupScheduleRule match(String orgId, String diseaseCode) {
            String org = orgId == null ? "" : orgId;
            String[][] order = {{org, diseaseCode}, {org, ""}, {"", diseaseCode}, {"", ""}};
            for (String[] scope : order) {
                FollowupScheduleRule r = byScope.get(key(scope[0], scope[1]));
                if (r != null) {
                    return r;
                }
            }
            return null;
        }

        private static String key(String orgId, String diseaseCode) {
            return (orgId == null ? "" : orgId) + "|" + (diseaseCode == null ? "" : diseaseCode);
        }
    }

    public record SaveCommand(
            String id, String orgId, String diseaseCode, Integer intervalWithPlan, Integer intervalNoPlan, Boolean enabled) {}

    public record RuleView(
            String id,
            String orgId,
            String orgName,
            String diseaseCode,
            String diseaseLabel,
            Integer intervalWithPlan,
            Integer intervalNoPlan,
            boolean enabled,
            LocalDateTime gmtModified) {}
}
