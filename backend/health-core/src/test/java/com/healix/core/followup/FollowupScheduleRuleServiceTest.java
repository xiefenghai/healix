package com.healix.core.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.followup.domain.FollowupScheduleRule;
import com.healix.core.followup.mapper.FollowupScheduleRuleMapper;
import com.healix.core.followup.service.FollowupScheduleRuleService;
import com.healix.core.org.mapper.OrganizationMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FollowupScheduleRuleServiceTest {

    private static final String T = "t1";
    private static final String ORG = "o1";
    private static final String P = "p1";

    private final FollowupScheduleRuleMapper ruleMapper = mock(FollowupScheduleRuleMapper.class);
    private final PeopleDiseaseArchiveMapper diseaseMapper = mock(PeopleDiseaseArchiveMapper.class);
    private final FollowupScheduleRuleService service =
            new FollowupScheduleRuleService(ruleMapper, diseaseMapper, mock(OrganizationMapper.class));
    private final List<FollowupScheduleRule> rules = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(ruleMapper.listByTenant(T)).thenReturn(rules);
        diseases();
    }

    @Test
    void noRulesFallsBackToSystemDefault() {
        assertEquals(30, resolve(true));
        assertEquals(90, resolve(false));
    }

    @Test
    void mostSpecificScopeWins() {
        rules.add(rule("", "", 60, 120, true));
        rules.add(rule("", "diabetes", 20, 40, true));
        rules.add(rule(ORG, "", 25, 50, true));
        diseases("diabetes");
        assertEquals(25, resolve(true));

        rules.add(rule(ORG, "diabetes", 14, 28, true));
        assertEquals(28, resolve(false));
    }

    @Test
    void multipleDiseasesTakeShortestEnabledInterval() {
        rules.add(rule("", "diabetes", 30, 60, true));
        rules.add(rule("", "hypertension", 21, 45, true));
        rules.add(rule("", "obesity", 7, 7, false));
        diseases("diabetes", "hypertension", "obesity");
        assertEquals(21, resolve(true));
    }

    @Test
    void allMatchedRulesDisabledSkipsScheduling() {
        rules.add(rule(ORG, "", 30, 60, false));
        assertNull(resolve(true));
        diseases("diabetes");
        assertNull(resolve(true));
    }

    private Integer resolve(boolean hasPlan) {
        return service.resolveIntervalDays(service.loadRuleSet(T), T, ORG, P, hasPlan);
    }

    private void diseases(String... codes) {
        List<PeopleDiseaseArchive> rows = new ArrayList<>();
        for (String c : codes) {
            PeopleDiseaseArchive a = new PeopleDiseaseArchive();
            a.setDiseaseCode(c);
            rows.add(a);
        }
        when(diseaseMapper.listByTenantAndPeople(T, P)).thenReturn(rows);
    }

    private static FollowupScheduleRule rule(String org, String disease, int withPlan, int noPlan, boolean enabled) {
        FollowupScheduleRule r = new FollowupScheduleRule();
        r.setTenantId(T);
        r.setOrgId(org);
        r.setDiseaseCode(disease);
        r.setIntervalWithPlan(withPlan);
        r.setIntervalNoPlan(noPlan);
        r.setEnabled(enabled ? 1 : 0);
        return r;
    }
}
