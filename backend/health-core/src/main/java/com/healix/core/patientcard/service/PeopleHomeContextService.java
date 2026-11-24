package com.healix.core.patientcard.service;

import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * C 会话「家庭租户」解析：账号本身无租户；业务表过渡期仍可能要 tenant_id。
 * <p>优先级：ACTIVE membership → people.tenant_id → 调用方兜底。
 */
@Service
@RequiredArgsConstructor
public class PeopleHomeContextService {

    private final PatientOrgMembershipMapper membershipMapper;
    private final PeopleProfileMapper peopleProfileMapper;

    public String resolveHomeTenantId(String peopleId) {
        if (!StringUtils.hasText(peopleId)) {
            return null;
        }
        List<PatientOrgMembership> memberships = membershipMapper.listActiveByPeople(peopleId);
        if (memberships != null && !memberships.isEmpty() && StringUtils.hasText(memberships.get(0).getTenantId())) {
            return memberships.get(0).getTenantId();
        }
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile != null && StringUtils.hasText(profile.getTenantId())) {
            return profile.getTenantId();
        }
        return null;
    }

    public boolean hasActiveMembership(String peopleId) {
        if (!StringUtils.hasText(peopleId)) {
            return false;
        }
        List<PatientOrgMembership> memberships = membershipMapper.listActiveByPeople(peopleId);
        return memberships != null && !memberships.isEmpty();
    }
}
