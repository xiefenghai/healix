package com.healix.core.patientcard.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.identity.enums.EnableStatusEnum;
import com.healix.core.identity.service.IdentityService;
import com.healix.core.org.domain.OrgInviteCode;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrgInviteCodeMapper;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.domain.PeopleActivationInvite;
import com.healix.core.patientcard.mapper.PeopleActivationInviteMapper;
import com.healix.core.people.domain.PeopleAccount;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleAccountMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.portal.enums.PortalEnum;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PatientActivationService {

    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PeopleActivationInviteMapper inviteMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleAccountMapper peopleAccountMapper;
    private final OrganizationMapper organizationMapper;
    private final OrgInviteCodeMapper orgInviteCodeMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PatientCardService patientCardService;
    private final AuditService auditService;
    private final IdentityService identityService;

    @Transactional
    public PeopleActivationInvite issue(
            String tenantId, String orgId, String peopleId, String staffId, int validDays) {
        PeopleProfile people = peopleProfileMapper.findById(peopleId);
        if (people == null || !tenantId.equals(people.getTenantId())) {
            throw new BusinessException(404, "患者不存在");
        }
        Organization org = organizationMapper.findById(orgId);
        if (org == null || !tenantId.equals(org.getTenantId())) {
            throw new BusinessException(404, "机构不存在");
        }
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(403, "患者未在本机构有效入组");
        }

        int days = validDays > 0 ? validDays : 7;
        inviteMapper.disableUnusedByPeople(peopleId);

        PeopleActivationInvite invite = new PeopleActivationInvite();
        invite.setTenantId(tenantId);
        invite.setOrgId(orgId);
        invite.setPeopleId(peopleId);
        invite.setCode(generateCode());
        invite.setEnabled(1);
        invite.setCreatedByStaffId(staffId);
        EntityMeta.onCreate(invite);
        invite.setExpireAt(invite.getGmtCreated().plusDays(days));
        inviteMapper.insert(invite);
        return invite;
    }

    public List<PeopleActivationInvite> listByPeople(String peopleId) {
        return inviteMapper.listByPeople(peopleId);
    }

    @Transactional
    public void revoke(String peopleId, String inviteId) {
        PeopleActivationInvite invite = inviteMapper.findById(inviteId);
        if (invite == null || !peopleId.equals(invite.getPeopleId())) {
            throw new BusinessException(404, "激活码不存在");
        }
        if (invite.getUsedAt() != null) {
            throw new BusinessException(409, "激活码已使用，无法作废");
        }
        inviteMapper.softDelete(inviteId, LocalDateTime.now());
    }

    /**
     * 激活：冷启动可建账号；热启动仅加卡片。返回 account + card。
     */
    @Transactional
    public ActivateResult activate(
            String tenantId,
            String activationCode,
            String username,
            String password,
            String displayName,
            String relation,
            String existingAccountId) {
        PeopleActivationInvite invite = requireUsableInvite(activationCode);
        if (StringUtils.hasText(tenantId) && !tenantId.equals(invite.getTenantId())) {
            throw new BusinessException(403, "租户不匹配");
        }
        PeopleProfile people = peopleProfileMapper.findById(invite.getPeopleId());
        if (people == null) {
            throw new BusinessException(404, "患者不存在");
        }
        if (StringUtils.hasText(displayName)
                && !people.getDisplayName().trim().equals(displayName.trim())) {
            throw new BusinessException(400, "姓名与档案不一致");
        }

        PeopleAccount account;
        if (StringUtils.hasText(existingAccountId)) {
            account = peopleAccountMapper.findById(existingAccountId);
            if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
                throw new BusinessException(401, "未登录或账号无效");
            }
            if (!invite.getTenantId().equals(account.getTenantId())) {
                throw new BusinessException(403, "租户不匹配");
            }
        } else {
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new BusinessException(400, "请设置用户名和密码");
            }
            account = identityService.registerAccount(invite.getTenantId(), username, password);
        }

        AccountPatient card = patientCardService.linkExistingPeople(
                invite.getTenantId(), account.getId(), people, relation, people.getDisplayName());

        ensureMembership(invite.getOrgId(), invite.getTenantId(), people.getId(), invite.getCreatedByStaffId());

        LocalDateTime now = LocalDateTime.now();
        invite.setUsedAt(now);
        invite.setUsedByAccountId(account.getId());
        invite.setGmtModified(now);
        inviteMapper.markUsed(invite);

        auditService.record(
                PortalEnum.C.code(),
                account.getId(),
                "PEOPLE",
                invite.getTenantId(),
                AuditActionEnum.PATIENT_CARD_ACTIVATE.name(),
                "account_patient",
                card.getId(),
                people.getId(),
                AuditDetails.of("orgId", invite.getOrgId(), "inviteId", invite.getId()));

        return new ActivateResult(account, card, people);
    }

    private PeopleActivationInvite requireUsableInvite(String activationCode) {
        if (!StringUtils.hasText(activationCode)) {
            throw new BusinessException(400, "请输入激活码");
        }
        String normalized = activationCode.trim().toUpperCase(Locale.ROOT).replace("-", "");
        PeopleActivationInvite invite = inviteMapper.findByCode(formatCode(normalized));
        if (invite == null) {
            invite = inviteMapper.findByCode(normalized);
        }
        if (invite == null) {
            // 常见误用：把机构邀请码当成患者激活码
            OrgInviteCode orgInvite = orgInviteCodeMapper.findByCode(normalized);
            if (orgInvite == null) {
                orgInvite = orgInviteCodeMapper.findByCode(formatCode(normalized));
            }
            if (orgInvite != null) {
                throw new BusinessException(
                        400,
                        "这是机构邀请码，不能用于激活。请到 B 端「患者详情」点击「生成激活码」；机构码请在登录并选择就诊人后用于入组");
            }
            throw new BusinessException(400, "激活码无效或已过期");
        }
        if (invite.getEnabled() == null || invite.getEnabled() != 1) {
            throw new BusinessException(400, "激活码无效或已过期（可能已重新发码，请使用最新码）");
        }
        if (invite.getUsedAt() != null) {
            throw new BusinessException(400, "激活码无效或已过期");
        }
        if (invite.getExpireAt() != null && invite.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "激活码无效或已过期");
        }
        return invite;
    }

    private void ensureMembership(String orgId, String tenantId, String peopleId, String staffId) {
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null) {
            membership = new PatientOrgMembership();
            membership.setTenantId(tenantId);
            membership.setOrgId(orgId);
            membership.setPeopleId(peopleId);
            membership.setStatus(MembershipStatusEnum.ACTIVE.name());
            membership.setInvitedByStaffId(staffId);
            EntityMeta.onCreate(membership);
            membership.setJoinedAt(membership.getGmtCreated());
            membershipMapper.insert(membership);
        } else if (!MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(409, "该患者机构关系已退出，请联系管理员处理");
        }

        if (careAssignmentMapper.find(tenantId, peopleId) == null) {
            PatientCareAssignment care = new PatientCareAssignment();
            care.setTenantId(tenantId);
            care.setPeopleId(peopleId);
            care.setPrimaryOrgId(orgId);
            EntityMeta.onCreate(care);
            careAssignmentMapper.insert(care);
        }
    }

    private String generateCode() {
        StringBuilder raw = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            raw.append(CODE_CHARS[RANDOM.nextInt(CODE_CHARS.length)]);
        }
        return formatCode(raw.toString());
    }

    private static String formatCode(String raw8) {
        String s = raw8.replace("-", "").toUpperCase(Locale.ROOT);
        if (s.length() != 8) {
            return s;
        }
        return s.substring(0, 4) + "-" + s.substring(4);
    }

    public record ActivateResult(PeopleAccount account, AccountPatient card, PeopleProfile people) {}
}
