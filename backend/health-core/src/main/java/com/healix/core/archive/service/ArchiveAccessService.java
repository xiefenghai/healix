package com.healix.core.archive.service;

import com.healix.common.exception.BusinessException;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ArchiveAccessService {

    private final PeopleProfileMapper peopleProfileMapper;
    private final PatientOrgMembershipMapper membershipMapper;

    public PeopleProfile requirePeopleInTenant(String tenantId, String peopleId) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException(404, "患者不存在");
        }
        if (!tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(403, "患者不属于当前租户");
        }
        return profile;
    }

    public void assertStaffCanAccessPeople(String tenantId, String orgId, String peopleId) {
        requirePeopleInTenant(tenantId, peopleId);
        if (orgId == null) {
            return;
        }
        var membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(403, "患者未在本机构有效入组");
        }
    }
}
