package com.healix.core.patient.service;

import com.healix.common.util.AuditDetails;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.org.domain.OrgInviteCode;
import com.healix.core.org.mapper.OrgInviteCodeMapper;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.portal.enums.PortalEnum;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者机构入组（邀请码）与照护分配钩子。
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final PatientOrgMembershipMapper membershipMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final OrgInviteCodeMapper inviteCodeMapper;
    private final AuditService auditService;

    @Transactional
    public PatientOrgMembership joinByInvite(String peopleId, String peopleAccountId, String inviteCode) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException("患者不存在");
        }
        OrgInviteCode invite = inviteCodeMapper.findByCode(inviteCode);
        if (invite == null || invite.getEnabled() == null || invite.getEnabled() != 1) {
            throw new BusinessException("邀请码无效");
        }
        if (invite.getExpireAt() != null && invite.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("邀请码已过期");
        }
        String orgId = invite.getOrgId();
        String tenantId = invite.getTenantId();
        if (!tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(403, "患者与租户不匹配");
        }

        PatientOrgMembership existing = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (existing != null && MembershipStatusEnum.ACTIVE.matches(existing.getStatus())) {
            return existing;
        }

        boolean firstInTenant = membershipMapper.countActiveInTenant(peopleId, tenantId) == 0;

        if (existing != null) {
            throw new BusinessException(409, "该患者机构关系已退出，请联系管理员处理");
        }

        PatientOrgMembership membership = new PatientOrgMembership();
        membership.setTenantId(tenantId);
        membership.setOrgId(orgId);
        membership.setPeopleId(peopleId);
        membership.setStatus(MembershipStatusEnum.ACTIVE.name());
        membership.setLeftAt(null);
        membership.setInvitedByStaffId(null);
        EntityMeta.onCreate(membership);
        membership.setJoinedAt(membership.getGmtCreated());
        membershipMapper.insert(membership);

        if (firstInTenant) {
            PatientCareAssignment care = careAssignmentMapper.find(tenantId, peopleId);
            if (care == null) {
                care = new PatientCareAssignment();
                care.setTenantId(tenantId);
                care.setPeopleId(peopleId);
                care.setPrimaryOrgId(orgId);
                EntityMeta.onCreate(care);
                careAssignmentMapper.insert(care);
            }
        }

        auditService.record(
                PortalEnum.C.code(),
                peopleAccountId,
                "PEOPLE",
                tenantId,
                AuditActionEnum.MEMBERSHIP_JOIN.name(),
                "people_org_membership",
                String.valueOf(membership.getId()),
                peopleId,
                AuditDetails.of("orgId", orgId));
        return membership;
    }

    public List<PatientOrgMembership> listActiveByOrg(String orgId) {
        return membershipMapper.listActiveByOrg(orgId);
    }

    public boolean hasActiveMembershipInTenant(String peopleId, String tenantId) {
        return membershipMapper.countActiveInTenant(peopleId, tenantId) > 0;
    }
}
