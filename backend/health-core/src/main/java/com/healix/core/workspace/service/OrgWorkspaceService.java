package com.healix.core.workspace.service;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.IdCardCrypto;
import com.healix.common.util.IdCardUtil;
import com.healix.common.util.SnowflakeId;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.enums.CareTeamMemberTypeEnum;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.identity.domain.StaffAccount;
import com.healix.core.identity.domain.StaffOrgBinding;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.domain.StaffRoleBinding;
import com.healix.core.identity.enums.EnableStatusEnum;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.identity.mapper.StaffAccountMapper;
import com.healix.core.identity.mapper.StaffOrgBindingMapper;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.identity.mapper.StaffRoleBindingMapper;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.archive.dto.ArchiveCompletenessDto;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveCompletenessService;
import com.healix.core.archive.service.BasicArchiveService;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.assessment.dto.AssessmentTagView;
import com.healix.core.assessment.support.AssessmentTagAssembler;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.govern.service.QuotaService;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import com.healix.core.people.domain.PeopleIdentity;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.people.enums.EducationLevelEnum;
import com.healix.core.people.enums.MaritalStatusEnum;
import com.healix.core.people.enums.MobileOwnerEnum;
import com.healix.core.people.enums.PeopleIdentityTypeEnum;
import com.healix.core.people.mapper.PeopleIdentityMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.people.support.DiseaseCodeLabels;
import com.healix.core.identity.service.IdentityService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.workspace.domain.StaffPatientWatch;
import com.healix.core.workspace.dto.CareTeamListItem;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.dto.OrgStaffItem;
import com.healix.core.workspace.mapper.StaffPatientWatchMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.healix.core.reward.service.RewardQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OrgWorkspaceService {

    private final OrganizationMapper organizationMapper;
    private final StaffAccountMapper staffAccountMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final StaffOrgBindingMapper staffOrgBindingMapper;
    private final StaffRoleBindingMapper staffRoleBindingMapper;
    private final CareTeamMapper careTeamMapper;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleIdentityMapper peopleIdentityMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final BasicArchiveService basicArchiveService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final ArchiveCompletenessService archiveCompletenessService;
    private final AssessmentTagAssembler assessmentTagAssembler;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final IdCardCrypto idCardCrypto;
    private final AccountPatientMapper accountPatientMapper;
    private final ObjectProvider<WorkspaceTaskGenerator> workspaceTaskGenerator;
    private final QuotaService quotaService;
    private final StaffPatientWatchMapper staffPatientWatchMapper;
    private final ObjectProvider<RewardQueryService> rewardQueryService;

    public void requireOrgWorkspaceAccess(String orgId) {
        String tenantId = requireTenantId();
        Organization org = organizationMapper.findById(orgId);
        if (org == null || !tenantId.equals(org.getTenantId())) {
            throw new BusinessException(404, "机构不存在");
        }
        if (isTenantAdmin()) {
            return;
        }
        String staffId = requireStaffId();
        if (staffOrgBindingMapper.countBinding(staffId, orgId) <= 0) {
            throw new BusinessException(403, "无权访问该机构");
        }
        Set<String> roles = currentRoles();
        if (!roles.contains(StaffRoleEnum.CARE_MANAGER.name())
                && !roles.contains(StaffRoleEnum.DOCTOR.name())
                && !roles.contains(StaffRoleEnum.TENANT_ADMIN.name())) {
            throw new BusinessException(403, "需要健管师或医生角色");
        }
    }

    public List<OrgStaffItem> listOrgStaff(String orgId, String roleCode, String keyword) {
        requireOrgWorkspaceAccess(orgId);
        LinkedHashSet<String> staffIds = new LinkedHashSet<>();
        for (StaffOrgBinding binding : staffOrgBindingMapper.listByOrg(orgId)) {
            staffIds.add(binding.getStaffId());
        }
        // 租户管理员默认无机构绑定，但仍可担任主责健管师 / 入组
        staffIds.addAll(
                staffRoleBindingMapper.listStaffIdsByTenantAndRole(
                        requireTenantId(), StaffRoleEnum.TENANT_ADMIN.name()));
        if (staffIds.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> rolesByStaff = loadRoles(new ArrayList<>(staffIds));
        List<OrgStaffItem> items = new ArrayList<>();
        for (String staffId : staffIds) {
            List<String> roles = rolesByStaff.getOrDefault(staffId, List.of());
            boolean eligible =
                    roles.contains(StaffRoleEnum.CARE_MANAGER.name())
                            || roles.contains(StaffRoleEnum.DOCTOR.name())
                            || roles.contains(StaffRoleEnum.TENANT_ADMIN.name());
            if (!eligible) {
                continue;
            }
            if (StringUtils.hasText(roleCode) && !roles.contains(roleCode)) {
                continue;
            }
            StaffProfile profile = staffProfileMapper.findById(staffId);
            if (profile == null) {
                continue;
            }
            StaffAccount account = staffAccountMapper.findById(profile.getAccountId());
            if (account == null) {
                continue;
            }
            if (StringUtils.hasText(keyword)) {
                String q = keyword.trim();
                boolean hit = (profile.getDisplayName() != null && profile.getDisplayName().contains(q))
                        || (account.getUsername() != null && account.getUsername().contains(q));
                if (!hit) {
                    continue;
                }
            }
            OrgStaffItem item = new OrgStaffItem();
            item.setStaffId(staffId);
            item.setAccountId(account.getId());
            item.setUsername(account.getUsername());
            item.setDisplayName(profile.getDisplayName());
            item.setMobile(profile.getMobile());
            item.setTitle(profile.getTitle());
            item.setStatus(account.getStatus());
            item.setRoles(roles);
            item.setCreatedAt(profile.getGmtCreated());
            items.add(item);
        }
        return items;
    }

    @Transactional
    public OrgStaffItem createOrgStaff(
            String orgId,
            String username,
            String password,
            String displayName,
            String mobile,
            String title,
            String roleCode,
            String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        String tenantId = requireTenantId();
        if (!StaffRoleEnum.CARE_MANAGER.name().equals(roleCode)
                && !StaffRoleEnum.DOCTOR.name().equals(roleCode)) {
            throw new BusinessException(400, "角色只能是健管师或医生");
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password) || !StringUtils.hasText(displayName)) {
            throw new BusinessException(400, "请填写用户名、密码和姓名");
        }
        if (password.length() < 8) {
            throw new BusinessException(400, "密码至少 8 位");
        }
        if (staffAccountMapper.findByUsername(username.trim()) != null) {
            throw new BusinessException(409, "员工用户名已存在");
        }
        quotaService.assertAvailable(tenantId, QuotaKeyEnum.STAFF_TOTAL, 1);
        StaffAccount account = new StaffAccount();
        account.setUsername(username.trim());
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(account);
        staffAccountMapper.insert(account);

        StaffProfile profile = new StaffProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(account.getId());
        profile.setDisplayName(displayName.trim());
        profile.setMobile(blankToNull(mobile));
        profile.setTitle(blankToNull(title));
        EntityMeta.onCreate(profile);
        staffProfileMapper.insert(profile);

        StaffRoleBinding role = new StaffRoleBinding();
        role.setTenantId(tenantId);
        role.setStaffId(profile.getId());
        role.setRoleCode(roleCode);
        role.setOrgId(null);
        EntityMeta.onCreate(role);
        staffRoleBindingMapper.insert(role);

        StaffOrgBinding binding = new StaffOrgBinding();
        binding.setTenantId(tenantId);
        binding.setStaffId(profile.getId());
        binding.setOrgId(orgId);
        binding.setIsDefault(1);
        EntityMeta.onCreate(binding);
        staffOrgBindingMapper.insert(binding);

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.ORG_STAFF_CREATE.name(),
                "staff_account",
                String.valueOf(account.getId()),
                null,
                AuditDetails.of("staffId", profile.getId(), "orgId", orgId, "roleCode", roleCode));

        OrgStaffItem item = new OrgStaffItem();
        item.setStaffId(profile.getId());
        item.setAccountId(account.getId());
        item.setUsername(account.getUsername());
        item.setDisplayName(profile.getDisplayName());
        item.setMobile(profile.getMobile());
        item.setTitle(profile.getTitle());
        item.setStatus(account.getStatus());
        item.setRoles(List.of(roleCode));
        item.setCreatedAt(profile.getGmtCreated());
        return item;
    }

    @Transactional
    public void unbindOrgStaff(String orgId, String staffId, String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        String tenantId = requireTenantId();
        StaffProfile profile = staffProfileMapper.findById(staffId);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(404, "员工不存在");
        }
        if (staffOrgBindingMapper.countBinding(staffId, orgId) <= 0) {
            throw new BusinessException(404, "员工未绑定该机构");
        }
        if (careTeamMemberMapper.countPrimaryTeamsForStaff(orgId, staffId) > 0) {
            throw new BusinessException(403, "该员工仍是健管组主责，请先更换主责后再解绑");
        }
        LocalDateTime now = LocalDateTime.now();
        staffOrgBindingMapper.softDeleteByStaffAndOrg(staffId, orgId, now, now);
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.ORG_STAFF_UNBIND.name(),
                "staff_org",
                String.valueOf(staffId),
                null,
                AuditDetails.of("orgId", orgId));
    }

    public List<CareTeamListItem> listCareTeams(String orgId, String keyword) {
        requireOrgWorkspaceAccess(orgId);
        List<CareTeam> teams = careTeamMapper.listByOrg(orgId, blankToNull(keyword));
        List<CareTeamListItem> items = new ArrayList<>();
        for (CareTeam team : teams) {
            CareTeamListItem item = toTeamItem(team);
            items.add(item);
        }
        return items;
    }

    public CareTeamListItem getCareTeam(String orgId, String teamId) {
        requireOrgWorkspaceAccess(orgId);
        return toTeamItem(requireTeamInOrg(orgId, teamId));
    }

    @Transactional
    public CareTeamListItem createCareTeam(
            String orgId,
            String name,
            String primaryCareManagerStaffId,
            String primaryDoctorStaffId,
            String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        String tenantId = requireTenantId();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "请填写名称");
        }
        if (primaryCareManagerStaffId == null) {
            throw new BusinessException(400, "请选择主责健管师");
        }
        assertOrgClinicalStaff(orgId, primaryCareManagerStaffId, StaffRoleEnum.CARE_MANAGER);
        if (primaryDoctorStaffId != null) {
            assertOrgClinicalStaff(orgId, primaryDoctorStaffId, StaffRoleEnum.DOCTOR);
        }
        CareTeam team = new CareTeam();
        team.setTenantId(tenantId);
        team.setOrgId(orgId);
        team.setTeamCode(SnowflakeId.nextCode("02", 32));
        team.setName(name.trim());
        team.setPrimaryCareManagerStaffId(primaryCareManagerStaffId);
        team.setPrimaryDoctorStaffId(primaryDoctorStaffId);
        team.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(team);
        careTeamMapper.insert(team);

        ensureStaffMember(team, primaryCareManagerStaffId);
        if (primaryDoctorStaffId != null) {
            ensureStaffMember(team, primaryDoctorStaffId);
        }

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.CARE_TEAM_CREATE.name(),
                "care_team",
                String.valueOf(team.getId()),
                null,
                AuditDetails.of("name", team.getName()));
        return toTeamItem(team);
    }

    @Transactional
    public CareTeamListItem updateCareTeamName(String orgId, String teamId, String name, String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        CareTeam team = requireTeamInOrg(orgId, teamId);
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "请填写名称");
        }
        team.setName(name.trim());
        EntityMeta.onUpdate(team);
        careTeamMapper.update(team);
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                AuditActionEnum.CARE_TEAM_UPDATE.name(),
                "care_team",
                String.valueOf(teamId),
                null,
                AuditDetails.of("name", team.getName()));
        return toTeamItem(team);
    }

    @Transactional
    public void deleteCareTeam(String orgId, String teamId, String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        CareTeam team = requireTeamInOrg(orgId, teamId);
        EntityMeta.onSoftDelete(team);
        careTeamMemberMapper.softDeleteByTeam(teamId, team.getGmtDeleted(), team.getGmtModified());
        careTeamMapper.softDelete(teamId, team.getGmtDeleted(), team.getGmtModified());
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                AuditActionEnum.CARE_TEAM_DELETE.name(),
                "care_team",
                String.valueOf(teamId),
                null,
                null);
    }

    @Transactional
    public CareTeamListItem changePrimary(
            String orgId,
            String teamId,
            String primaryCareManagerStaffId,
            String primaryDoctorStaffId,
            String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        CareTeam team = requireTeamInOrg(orgId, teamId);
        if (primaryCareManagerStaffId == null) {
            throw new BusinessException(400, "请选择主责健管师");
        }
        assertOrgClinicalStaff(orgId, primaryCareManagerStaffId, StaffRoleEnum.CARE_MANAGER);
        if (primaryDoctorStaffId != null) {
            assertOrgClinicalStaff(orgId, primaryDoctorStaffId, StaffRoleEnum.DOCTOR);
        }
        boolean cmChanged = !primaryCareManagerStaffId.equals(team.getPrimaryCareManagerStaffId());
        String oldCm = team.getPrimaryCareManagerStaffId();
        team.setPrimaryCareManagerStaffId(primaryCareManagerStaffId);
        team.setPrimaryDoctorStaffId(primaryDoctorStaffId);
        EntityMeta.onUpdate(team);
        careTeamMapper.update(team);
        ensureStaffMember(team, primaryCareManagerStaffId);
        if (primaryDoctorStaffId != null) {
            ensureStaffMember(team, primaryDoctorStaffId);
        }
        if (cmChanged) {
            syncCareAssignmentForTeamPatients(team);
            WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
            if (gen != null) {
                for (CareTeamMember member : careTeamMemberMapper.listByTeam(team.getId())) {
                    if (CareTeamMemberTypeEnum.PATIENT.matches(member.getMemberType())) {
                        gen.onPrimaryCareManagerChanged(
                                orgId, member.getPeopleId(), oldCm, primaryCareManagerStaffId);
                    }
                }
            }
        }
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                AuditActionEnum.CARE_TEAM_PRIMARY_CHANGE.name(),
                "care_team",
                String.valueOf(teamId),
                null,
                AuditDetails.of("primaryCareManagerStaffId", primaryCareManagerStaffId));
        return toTeamItem(team);
    }

    public List<CareTeamMember> listMembers(String orgId, String teamId) {
        requireOrgWorkspaceAccess(orgId);
        requireTeamInOrg(orgId, teamId);
        return careTeamMemberMapper.listByTeam(teamId);
    }

    @Transactional
    public CareTeamMember addMember(
            String orgId, String teamId, String memberType, String staffId, String patientId, String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        CareTeam team = requireTeamInOrg(orgId, teamId);
        CareTeamMemberTypeEnum type = CareTeamMemberTypeEnum.valueOf(memberType);
        if (type == CareTeamMemberTypeEnum.STAFF) {
            if (staffId == null) {
                throw new BusinessException(400, "请选择员工");
            }
            assertOrgClinicalStaff(orgId, staffId, null);
            CareTeamMember existing = careTeamMemberMapper.findStaff(teamId, staffId);
            if (existing != null) {
                return existing;
            }
            CareTeamMember member = newStaffMember(team, staffId);
            careTeamMemberMapper.insert(member);
            auditAddMember(actorAccountId, teamId, member);
            return member;
        }
        if (patientId == null) {
            throw new BusinessException(400, "请选择患者");
        }
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, patientId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(400, "患者须先成为本机构有效成员");
        }
        CareTeamMember existingInOrg = careTeamMemberMapper.findPeopleInOrg(orgId, patientId);
        if (existingInOrg != null) {
            throw new BusinessException(400, "该患者已在本机构的其他健管组中");
        }
        CareTeamMember member = new CareTeamMember();
        member.setTenantId(team.getTenantId());
        member.setOrgId(orgId);
        member.setTeamId(teamId);
        member.setMemberType(CareTeamMemberTypeEnum.PATIENT.name());
        member.setPeopleId(patientId);
        EntityMeta.onCreate(member);
        member.setJoinedAt(member.getGmtCreated());
        careTeamMemberMapper.insert(member);
        upsertCareAssignment(team.getTenantId(), patientId, orgId, team.getPrimaryCareManagerStaffId());
        auditAddMember(actorAccountId, teamId, member);
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            StaffProfile actor = staffProfileMapper.findByAccountId(actorAccountId);
            gen.onPatientJoinedTeam(
                    team.getTenantId(),
                    orgId,
                    patientId,
                    team.getPrimaryCareManagerStaffId(),
                    actor == null ? null : actor.getId());
        }
        return member;
    }

    /**
     * 将患者加入或变更到指定健管组（机构内一人仅属一组）。
     *
     * <p>已在目标组则幂等返回；从其他组迁出时不关闭待办，仅按新主责重派。
     */
    @Transactional
    public OrgPatientListItem changePatientCareTeam(
            String orgId, String peopleId, String targetTeamId, String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        if (!StringUtils.hasText(peopleId) || !StringUtils.hasText(targetTeamId)) {
            throw new BusinessException(400, "请选择患者与健管组");
        }
        CareTeam target = requireTeamInOrg(orgId, targetTeamId);
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(400, "患者须先成为本机构有效成员");
        }

        CareTeamMember existing = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        if (existing != null && targetTeamId.equals(existing.getTeamId())) {
            return getOrgPatient(orgId, peopleId);
        }

        String fromTeamId = existing == null ? null : existing.getTeamId();
        String oldCmStaffId = null;
        if (existing != null) {
            CareTeam fromTeam = careTeamMapper.findById(existing.getTeamId());
            if (fromTeam != null) {
                oldCmStaffId = fromTeam.getPrimaryCareManagerStaffId();
            }
            // 不调用 onPatientLeftTeam：离组会过期全部待办，换组只需重派
            EntityMeta.onSoftDelete(existing);
            careTeamMemberMapper.softDelete(
                    existing.getId(), existing.getGmtDeleted(), existing.getGmtModified());
        }

        CareTeamMember member = new CareTeamMember();
        member.setTenantId(target.getTenantId());
        member.setOrgId(orgId);
        member.setTeamId(targetTeamId);
        member.setMemberType(CareTeamMemberTypeEnum.PATIENT.name());
        member.setPeopleId(peopleId);
        EntityMeta.onCreate(member);
        member.setJoinedAt(member.getGmtCreated());
        careTeamMemberMapper.insert(member);
        upsertCareAssignment(
                target.getTenantId(), peopleId, orgId, target.getPrimaryCareManagerStaffId());

        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            StaffProfile actor = staffProfileMapper.findByAccountId(actorAccountId);
            String actorStaffId = actor == null ? null : actor.getId();
            if (fromTeamId == null) {
                gen.onPatientJoinedTeam(
                        target.getTenantId(),
                        orgId,
                        peopleId,
                        target.getPrimaryCareManagerStaffId(),
                        actorStaffId);
            } else {
                gen.onPatientMovedTeam(
                        orgId, peopleId, oldCmStaffId, target.getPrimaryCareManagerStaffId());
            }
        }

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                fromTeamId == null
                        ? AuditActionEnum.CARE_TEAM_MEMBER_ADD.name()
                        : AuditActionEnum.CARE_TEAM_MEMBER_MOVE.name(),
                "care_team_member",
                String.valueOf(member.getId()),
                peopleId,
                AuditDetails.of(
                        "teamId",
                        targetTeamId,
                        "fromTeamId",
                        fromTeamId,
                        "memberType",
                        CareTeamMemberTypeEnum.PATIENT.name()));
        return getOrgPatient(orgId, peopleId);
    }

    @Transactional
    public void removeMember(String orgId, String teamId, String memberId, String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        CareTeam team = requireTeamInOrg(orgId, teamId);
        CareTeamMember member = careTeamMemberMapper.findById(memberId);
        if (member == null || !teamId.equals(member.getTeamId())) {
            throw new BusinessException(404, "成员不存在");
        }
        if (CareTeamMemberTypeEnum.STAFF.matches(member.getMemberType())
                && team.getPrimaryCareManagerStaffId().equals(member.getStaffId())) {
            throw new BusinessException(403, "不能直接移除主责健管师，请先更换主责");
        }
        if (CareTeamMemberTypeEnum.STAFF.matches(member.getMemberType())
                && team.getPrimaryDoctorStaffId() != null
                && team.getPrimaryDoctorStaffId().equals(member.getStaffId())) {
            team.setPrimaryDoctorStaffId(null);
            EntityMeta.onUpdate(team);
            careTeamMapper.update(team);
        }
        EntityMeta.onSoftDelete(member);
        careTeamMemberMapper.softDelete(memberId, member.getGmtDeleted(), member.getGmtModified());
        if (CareTeamMemberTypeEnum.PATIENT.matches(member.getMemberType())) {
            WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
            if (gen != null) {
                gen.onPatientLeftTeam(orgId, member.getPeopleId());
            }
        }
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                AuditActionEnum.CARE_TEAM_MEMBER_REMOVE.name(),
                "care_team_member",
                String.valueOf(memberId),
                null,
                AuditDetails.of("teamId", teamId));
    }

    public List<OrgPatientListItem> listOrgPatients(
            String orgId, String keyword, String careTeamId, Boolean unassigned) {
        return listOrgPatients(orgId, keyword, careTeamId, unassigned, null);
    }

    public List<OrgPatientListItem> listOrgPatients(
            String orgId, String keyword, String careTeamId, Boolean unassigned, Boolean watched) {
        requireOrgWorkspaceAccess(orgId);
        return listOrgPatientsInternal(orgId, keyword, careTeamId, unassigned, watched, true);
    }

    /**
     * 系统 / Job 用：不依赖 {@link RequestContext}，只校验机构属于给定租户。
     *
     * <p>平台定时任务禁止挂登录态；依从日快照等需要同一套患者宇宙时走这里。
     */
    public List<OrgPatientListItem> listOrgPatientsSystem(
            String tenantId, String orgId, String keyword, String careTeamId, Boolean unassigned) {
        Organization org = organizationMapper.findById(orgId);
        if (org == null || !tenantId.equals(org.getTenantId())) {
            throw new BusinessException(404, "机构不存在");
        }
        return listOrgPatientsInternal(orgId, keyword, careTeamId, unassigned, null, false);
    }

    private List<OrgPatientListItem> listOrgPatientsInternal(
            String orgId,
            String keyword,
            String careTeamId,
            Boolean unassigned,
            Boolean watched,
            boolean fillWatch) {
        Set<String> watchedPeopleIds = fillWatch ? currentWatchPeopleIds(orgId) : Set.of();
        List<PatientOrgMembership> memberships = membershipMapper.listActiveByOrg(orgId);
        List<OrgPatientListItem> items = new ArrayList<>();
        Map<String, String> tenantOf = new HashMap<>();
        for (PatientOrgMembership m : memberships) {
            PeopleProfile p = peopleProfileMapper.findById(m.getPeopleId());
            if (p == null) {
                continue;
            }
            if (StringUtils.hasText(keyword)) {
                String q = keyword.trim().toLowerCase();
                boolean hitName = p.getDisplayName() != null && p.getDisplayName().contains(keyword.trim());
                boolean hitPinyin =
                        p.getNamePinyin() != null && p.getNamePinyin().contains(q);
                if (!hitName && !hitPinyin) {
                    continue;
                }
            }
            CareTeamMember teamMember = careTeamMemberMapper.findPeopleInOrg(orgId, p.getId());
            if (Boolean.TRUE.equals(unassigned) && teamMember != null) {
                continue;
            }
            if (careTeamId != null) {
                if (teamMember == null || !careTeamId.equals(teamMember.getTeamId())) {
                    continue;
                }
            }
            boolean isWatched = watchedPeopleIds.contains(p.getId());
            if (Boolean.TRUE.equals(watched) && !isWatched) {
                continue;
            }
            OrgPatientListItem item = toPatientItem(p, m, teamMember, false);
            tenantOf.put(p.getId(), p.getTenantId());
            if (fillWatch) {
                item.setWatched(isWatched);
            }
            items.add(item);
        }
        fillAssessmentTags(items, tenantOf);
        if (fillWatch) {
            fillStreakDays(items);
        }
        return items;
    }

    /** 评估标签只读最新结果表，按租户批量取，避免列表 N+1。 */
    private void fillAssessmentTags(List<OrgPatientListItem> items, Map<String, String> tenantOf) {
        Map<String, List<OrgPatientListItem>> byTenant = new HashMap<>();
        for (OrgPatientListItem item : items) {
            String tenantId = tenantOf.get(item.getPeopleId());
            if (StringUtils.hasText(tenantId)) {
                byTenant.computeIfAbsent(tenantId, k -> new ArrayList<>()).add(item);
            }
        }
        byTenant.forEach((tenantId, group) -> {
            try {
                Map<String, List<AssessmentTagView>> tags = assessmentTagAssembler.assembleBatch(
                        tenantId, group.stream().map(OrgPatientListItem::getPeopleId).toList());
                group.forEach(i -> i.setAssessmentTags(tags.getOrDefault(i.getPeopleId(), List.of())));
            } catch (Exception ignored) {
                group.forEach(i -> i.setAssessmentTags(List.of()));
            }
        });
    }

    private void fillStreakDays(List<OrgPatientListItem> items) {
        RewardQueryService rewards = rewardQueryService.getIfAvailable();
        if (rewards == null || items.isEmpty()) {
            return;
        }
        Map<String, RewardQueryService.PatientBrief> briefs = rewards.patientBriefs(
                requireTenantId(), items.stream().map(OrgPatientListItem::getPeopleId).toList());
        items.forEach(i -> {
            RewardQueryService.PatientBrief b = briefs.get(i.getPeopleId());
            if (b != null) {
                i.setStreakDays(b.streak());
                i.setTodayActions(b.todayActions());
                i.setInactiveDays(b.inactiveDays());
            }
        });
    }

    public OrgPatientListItem getOrgPatient(String orgId, String peopleId) {
        requireOrgWorkspaceAccess(orgId);
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException(404, "患者不存在");
        }
        if (!requireTenantId().equals(profile.getTenantId())) {
            throw new BusinessException(403, "患者不属于当前租户");
        }
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(403, "患者未在本机构有效入组");
        }
        CareTeamMember teamMember = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        OrgPatientListItem item = toPatientItem(profile, membership, teamMember);
        item.setWatched(isWatched(orgId, peopleId));
        return item;
    }

    /** 将患者加入当前员工在本机构的重点关注（幂等）。 */
    @Transactional
    public OrgPatientListItem watchPatient(String orgId, String peopleId) {
        OrgPatientListItem item = getOrgPatient(orgId, peopleId);
        if (Boolean.TRUE.equals(item.getWatched())) {
            return item;
        }
        String staffId = requireStaffId();
        StaffPatientWatch row = new StaffPatientWatch();
        row.setTenantId(requireTenantId());
        row.setOrgId(orgId);
        row.setStaffId(staffId);
        row.setPeopleId(peopleId);
        EntityMeta.onCreate(row);
        staffPatientWatchMapper.insert(row);
        item.setWatched(true);
        return item;
    }

    /** 取消当前员工对本机构患者的重点关注（幂等）。 */
    @Transactional
    public OrgPatientListItem unwatchPatient(String orgId, String peopleId) {
        OrgPatientListItem item = getOrgPatient(orgId, peopleId);
        if (!Boolean.TRUE.equals(item.getWatched())) {
            return item;
        }
        LocalDateTime now = LocalDateTime.now();
        staffPatientWatchMapper.softDelete(requireStaffId(), orgId, peopleId, now, now);
        item.setWatched(false);
        return item;
    }

    private Set<String> currentWatchPeopleIds(String orgId) {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getStaffId() == null) {
            return Set.of();
        }
        List<String> ids = staffPatientWatchMapper.listPeopleIdsByStaffOrg(ctx.getStaffId(), orgId);
        return ids == null || ids.isEmpty() ? Set.of() : new HashSet<>(ids);
    }

    private boolean isWatched(String orgId, String peopleId) {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getStaffId() == null) {
            return false;
        }
        return staffPatientWatchMapper.findActive(ctx.getStaffId(), orgId, peopleId) != null;
    }

    /**
     * 建档：姓名 + 性别 + 出生日期为必要信息，证件选填；联系电话须填（可标「暂无」）并注明归属。
     *
     * @param attachPeopleId 查重确认「就是此人」时传入，复用已有档案并入组本机构
     */
    @Transactional
    public OrgPatientListItem createArchive(
            String orgId,
            String name,
            String attachPeopleId,
            String identityType,
            String identityValue,
            String gender,
            LocalDate birthday,
            String mobile,
            String mobileOwner,
            boolean noMobile,
            String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        String tenantId = requireTenantId();
        String staffId = requireStaffId();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "请填写姓名");
        }
        String mobileNorm = noMobile ? null : normalizeMobile(mobile);
        if (!noMobile && mobileNorm == null && attachPeopleId == null) {
            throw new BusinessException(400, "请填写联系电话，确实没有请勾选「暂无」");
        }
        String ownerNorm = mobileNorm == null ? null : MobileOwnerEnum.normalize(mobileOwner);
        boolean hasIdentity = StringUtils.hasText(identityValue);
        PeopleProfile profile;
        boolean createdProfile = false;
        String resolvedType = null;
        String resolvedMask = null;

        if (attachPeopleId != null) {
            profile = peopleProfileMapper.findById(attachPeopleId);
            if (profile == null || !tenantId.equals(profile.getTenantId())) {
                throw new BusinessException(404, "要关联的档案不存在");
            }
            if (hasIdentity) {
                IdentityPayload payload = resolveIdentity(tenantId, identityType, identityValue, gender, birthday);
                PeopleIdentity occupied =
                        peopleIdentityMapper.findByTenantTypeHash(tenantId, payload.type().name(), payload.hash());
                if (occupied != null && !occupied.getPeopleId().equals(profile.getId())) {
                    throw new BusinessException(409, "该证件已属于另一份档案，如为同一人请由租户管理员合并档案");
                }
                if (peopleIdentityMapper.findByPeopleAndType(profile.getId(), payload.type().name()) == null) {
                    insertIdentity(tenantId, profile.getId(), payload);
                }
                if (payload.gender() != null) {
                    profile.setGender(payload.gender());
                }
                if (payload.birthday() != null) {
                    profile.setBirthday(payload.birthday());
                }
                resolvedType = payload.type().name();
                resolvedMask = payload.mask();
            }
            fillBlankDemographics(profile, gender, birthday, mobileNorm, ownerNorm);
            EntityMeta.onUpdate(profile);
            peopleProfileMapper.updateProfile(profile);
        } else if (!hasIdentity) {
            if (!StringUtils.hasText(gender) || birthday == null) {
                throw new BusinessException(400, "请填写性别和出生日期");
            }
            profile = newPeople(tenantId, name.trim(), gender, birthday, mobileNorm, ownerNorm);
            createdProfile = true;
        } else {
            IdentityPayload payload = resolveIdentity(tenantId, identityType, identityValue, gender, birthday);
            PeopleIdentity existing =
                    peopleIdentityMapper.findByTenantTypeHash(tenantId, payload.type().name(), payload.hash());
            if (existing != null) {
                profile = peopleProfileMapper.findById(existing.getPeopleId());
                if (profile == null) {
                    throw new BusinessException(500, "证件数据异常，请联系管理员");
                }
                fillBlankDemographics(profile, payload.gender(), payload.birthday(), mobileNorm, ownerNorm);
                EntityMeta.onUpdate(profile);
                peopleProfileMapper.updateProfile(profile);
                resolvedType = existing.getIdentityType();
                resolvedMask = existing.getIdentityValueMask();
            } else {
                profile = newPeople(tenantId, name.trim(), payload.gender(), payload.birthday(), mobileNorm, ownerNorm);
                insertIdentity(tenantId, profile.getId(), payload);
                createdProfile = true;
                resolvedType = payload.type().name();
                resolvedMask = payload.mask();
            }
        }

        PatientOrgMembership membership = ensureMembership(orgId, tenantId, profile.getId(), staffId);
        ensureBasicArchive(tenantId, profile.getId(), staffId);
        ensureCareAssignment(orgId, tenantId, profile.getId());

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.PATIENT_ARCHIVE_CREATE.name(),
                "people_profile",
                String.valueOf(profile.getId()),
                profile.getId(),
                AuditDetails.of("orgId", orgId, "createdProfile", createdProfile));

        CareTeamMember tm = careTeamMemberMapper.findPeopleInOrg(orgId, profile.getId());
        OrgPatientListItem item = toPatientItem(profile, membership, tm);
        if (resolvedMask != null) {
            item.setIdentityMask(resolvedMask);
            item.setIdentityType(resolvedType);
        }
        return item;
    }

    /**
     * 建档前查重：同名且同生日，或同名且同号码。只返回辨认所需的最少信息。
     *
     * @param inCurrentOrg 已在本机构入组，无需再建
     */
    public record DuplicateCandidate(
            String peopleId,
            String displayName,
            String gender,
            LocalDate birthday,
            String mobileMasked,
            String mobileOwner,
            boolean hasIdentity,
            boolean inCurrentOrg) {}

    public List<DuplicateCandidate> findPossibleDuplicates(
            String orgId, String name, LocalDate birthday, String mobile) {
        requireOrgWorkspaceAccess(orgId);
        String tenantId = requireTenantId();
        if (!StringUtils.hasText(name) || (birthday == null && !StringUtils.hasText(mobile))) {
            return List.of();
        }
        List<DuplicateCandidate> out = new ArrayList<>();
        for (PeopleProfile p : peopleProfileMapper.listPossibleDuplicates(
                tenantId, name.trim(), birthday, normalizeBlank(mobile))) {
            PatientOrgMembership m = membershipMapper.findByOrgAndPeople(orgId, p.getId());
            out.add(new DuplicateCandidate(
                    p.getId(),
                    p.getDisplayName(),
                    p.getGender(),
                    p.getBirthday(),
                    maskMobile(p.getMobile()),
                    p.getMobileOwner(),
                    peopleIdentityMapper.findPrimaryMask(p.getId()) != null,
                    m != null && MembershipStatusEnum.ACTIVE.matches(m.getStatus())));
        }
        return out;
    }

    /** 已建档患者补充证件；证件属于另一份档案时提示合并，不自动处理。 */
    @Transactional
    public OrgPatientListItem addIdentity(
            String orgId, String peopleId, String identityType, String identityValue, String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        String tenantId = requireTenantId();
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(404, "患者不存在");
        }
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(403, "患者未在本机构有效入组");
        }
        IdentityPayload payload =
                resolveIdentity(tenantId, identityType, identityValue, profile.getGender(), profile.getBirthday());
        if (peopleIdentityMapper.findByPeopleAndType(peopleId, payload.type().name()) != null) {
            throw new BusinessException(409, "该患者已登记过此类证件");
        }
        PeopleIdentity occupied =
                peopleIdentityMapper.findByTenantTypeHash(tenantId, payload.type().name(), payload.hash());
        if (occupied != null) {
            PeopleProfile other = peopleProfileMapper.findById(occupied.getPeopleId());
            String otherName = other == null ? "另一份档案" : "「" + other.getDisplayName() + "」";
            throw new BusinessException(409, "该证件已登记在" + otherName + "名下，如为同一人请联系租户管理员合并档案");
        }
        insertIdentity(tenantId, peopleId, payload);
        if (payload.type() == PeopleIdentityTypeEnum.ID_CARD) {
            profile.setGender(payload.gender());
            profile.setBirthday(payload.birthday());
            EntityMeta.onUpdate(profile);
            peopleProfileMapper.updateProfile(profile);
        }
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.PATIENT_IDENTITY_ADD.name(),
                "people_identity",
                peopleId,
                peopleId,
                AuditDetails.of("orgId", orgId, "identityType", payload.type().name()));
        CareTeamMember tm = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        return toPatientItem(profile, membership, tm);
    }

    private static String maskMobile(String mobile) {
        if (!StringUtils.hasText(mobile)) {
            return null;
        }
        String m = mobile.trim();
        return m.length() >= 7 ? m.substring(0, 3) + "****" + m.substring(m.length() - 4) : "****";
    }

    private OrgPatientListItem toPatientItem(
            PeopleProfile p, PatientOrgMembership m, CareTeamMember teamMember) {
        return toPatientItem(p, m, teamMember, true);
    }

    private OrgPatientListItem toPatientItem(
            PeopleProfile p, PatientOrgMembership m, CareTeamMember teamMember, boolean withTags) {
        PeopleIdentity id = peopleIdentityMapper.findPrimaryMask(p.getId());
        backfillDemographicsFromIdentity(p, id);
        OrgPatientListItem item = new OrgPatientListItem();
        item.setPeopleId(p.getId());
        item.setDisplayName(p.getDisplayName());
        item.setGender(p.getGender());
        item.setBirthday(p.getBirthday());
        item.setJoinedAt(m.getJoinedAt());
        if (id != null) {
            item.setIdentityMask(id.getIdentityValueMask());
            item.setIdentityType(id.getIdentityType());
        }
        CareTeam team = null;
        if (teamMember != null) {
            team = careTeamMapper.findById(teamMember.getTeamId());
            if (team != null) {
                item.setCareTeamId(team.getId());
                item.setCareTeamName(team.getName());
            }
        }
        int cardCount = accountPatientMapper.countByPeople(p.getId());
        item.setClientCardCount(cardCount);
        item.setClientLinked(cardCount > 0);
        item.setMobile(p.getMobile());
        item.setMobileOwner(p.getMobileOwner());
        item.setAddress(p.getAddress());
        item.setEducationLevel(p.getEducationLevel());
        item.setMaritalStatus(p.getMaritalStatus());
        item.setOccupation(p.getOccupation());
        enrichClinicalSummary(item, p.getTenantId(), m.getOrgId(), p.getId(), team, withTags);
        return item;
    }

    /** 病种 / 评估标签 / 主责健管师 / 档案完整度（列表展示用，失败不阻断）。 */
    private void enrichClinicalSummary(
            OrgPatientListItem item, String tenantId, String orgId, String peopleId, CareTeam team, boolean withTags) {
        try {
            List<String> labels = new ArrayList<>();
            for (DiseaseArchiveViewDto row : diseaseArchiveService.list(tenantId, peopleId)) {
                if (!StringUtils.hasText(row.getDiseaseCode())) {
                    continue;
                }
                String label = DiseaseCodeLabels.label(row.getDiseaseCode());
                if (StringUtils.hasText(label)) {
                    labels.add(label);
                }
            }
            item.setDiseaseLabels(labels);
        } catch (Exception ignored) {
            item.setDiseaseLabels(List.of());
        }

        if (withTags) {
            try {
                List<AssessmentTagView> tags = assessmentTagAssembler.assemble(tenantId, orgId, peopleId);
                item.setAssessmentTags(tags == null ? List.of() : tags);
            } catch (Exception ignored) {
                item.setAssessmentTags(List.of());
            }
        }

        try {
            String cmStaffId = null;
            PatientCareAssignment assignment = careAssignmentMapper.find(tenantId, peopleId);
            if (assignment != null && StringUtils.hasText(assignment.getPrimaryCareManagerStaffId())) {
                cmStaffId = assignment.getPrimaryCareManagerStaffId();
            } else if (team != null && StringUtils.hasText(team.getPrimaryCareManagerStaffId())) {
                cmStaffId = team.getPrimaryCareManagerStaffId();
            }
            if (StringUtils.hasText(cmStaffId)) {
                item.setPrimaryCareManagerStaffId(cmStaffId);
                StaffProfile cm = staffProfileMapper.findById(cmStaffId);
                item.setPrimaryCareManagerName(cm == null ? null : cm.getDisplayName());
            }
        } catch (Exception ignored) {
            // ignore
        }

        try {
            ArchiveCompletenessDto c = archiveCompletenessService.compute(tenantId, peopleId);
            item.setArchiveCompletenessPercent(c.getPercent());
        } catch (Exception ignored) {
            item.setArchiveCompletenessPercent(null);
        }
    }

    /**
     * 更新患者人口学基础信息（姓名、联系方式、住址、文化程度、婚姻、职业）。
     */
    @Transactional
    public OrgPatientListItem updatePatientBasicInfo(
            String orgId,
            String peopleId,
            String displayName,
            String mobile,
            String mobileOwner,
            String address,
            String educationLevel,
            String maritalStatus,
            String occupation,
            String actorAccountId) {
        requireOrgWorkspaceAccess(orgId);
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException(404, "患者不存在");
        }
        if (!requireTenantId().equals(profile.getTenantId())) {
            throw new BusinessException(403, "患者不属于当前租户");
        }
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            throw new BusinessException(403, "患者未在本机构有效入组");
        }
        if (!StringUtils.hasText(displayName)) {
            throw new BusinessException(400, "请填写姓名");
        }
        String name = displayName.trim();
        if (name.length() > 64) {
            throw new BusinessException(400, "姓名过长");
        }
        String mobileNorm = normalizeBlank(mobile);
        if (mobileNorm != null) {
            if (mobileNorm.length() > 32) {
                throw new BusinessException(400, "联系方式过长");
            }
            if (!mobileNorm.matches("^[0-9+\\-\\s]{6,20}$")) {
                throw new BusinessException(400, "联系方式格式不正确");
            }
        }
        String addressNorm = normalizeBlank(address);
        if (addressNorm != null && addressNorm.length() > 256) {
            throw new BusinessException(400, "家庭住址过长");
        }
        String occupationNorm = normalizeBlank(occupation);
        if (occupationNorm != null && occupationNorm.length() > 64) {
            throw new BusinessException(400, "职业过长");
        }
        String educationNorm = normalizeBlank(educationLevel);
        if (educationNorm != null) {
            try {
                EducationLevelEnum.fromStored(educationNorm);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, e.getMessage());
            }
        }
        String maritalNorm = normalizeBlank(maritalStatus);
        if (maritalNorm != null) {
            try {
                MaritalStatusEnum.fromStored(maritalNorm);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, e.getMessage());
            }
        }

        profile.setDisplayName(name);
        profile.setNamePinyin(IdentityService.toPinyinKey(name));
        profile.setMobile(mobileNorm);
        profile.setMobileOwner(mobileNorm == null ? null : MobileOwnerEnum.normalize(mobileOwner));
        profile.setAddress(addressNorm);
        profile.setEducationLevel(educationNorm);
        profile.setMaritalStatus(maritalNorm);
        profile.setOccupation(occupationNorm);
        EntityMeta.onUpdate(profile);
        peopleProfileMapper.updateProfile(profile);
        accountPatientMapper.updateDisplayNameByPeopleId(peopleId, name, LocalDateTime.now());

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                profile.getTenantId(),
                AuditActionEnum.PATIENT_BASIC_INFO_UPDATE.name(),
                "people_profile",
                profile.getId(),
                profile.getId(),
                AuditDetails.of("orgId", orgId, "displayName", name));

        CareTeamMember tm = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        return toPatientItem(profile, membership, tm);
    }

    /** 历史档案缺的人口学信息 / 联系电话用本次填写补齐，不覆盖已有值。 */
    private static void fillBlankDemographics(
            PeopleProfile profile, String gender, LocalDate birthday, String mobile, String mobileOwner) {
        if (!StringUtils.hasText(profile.getGender()) && StringUtils.hasText(gender)) {
            profile.setGender(gender);
        }
        if (profile.getBirthday() == null && birthday != null) {
            profile.setBirthday(birthday);
        }
        if (!StringUtils.hasText(profile.getMobile()) && mobile != null) {
            profile.setMobile(mobile);
            profile.setMobileOwner(mobileOwner);
        }
    }

    /** 手机或座机；空返回 null。 */
    private static String normalizeMobile(String mobile) {
        String m = normalizeBlank(mobile);
        if (m == null) {
            return null;
        }
        if (m.length() > 32 || !m.matches("^[0-9+\\-\\s]{6,20}$")) {
            throw new BusinessException(400, "联系电话格式不正确");
        }
        return m;
    }

    private static String normalizeBlank(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 身份证建档后若 profile 缺生日/性别（历史数据或证件复用路径），从密文回填并落库。
     */
    private void backfillDemographicsFromIdentity(PeopleProfile profile, PeopleIdentity identity) {
        if (profile == null || identity == null) {
            return;
        }
        if (!PeopleIdentityTypeEnum.ID_CARD.name().equals(identity.getIdentityType())) {
            return;
        }
        boolean needGender = !StringUtils.hasText(profile.getGender());
        boolean needBirthday = profile.getBirthday() == null;
        if (!needGender && !needBirthday) {
            return;
        }
        if (!StringUtils.hasText(identity.getIdentityValueCipher())) {
            return;
        }
        try {
            String plain = idCardCrypto.decrypt(identity.getIdentityValueCipher());
            String idNo = plain;
            int sep = plain.indexOf('|');
            if (sep >= 0 && sep + 1 < plain.length()) {
                idNo = plain.substring(sep + 1);
            }
            if (!IdCardUtil.isValid(idNo)) {
                return;
            }
            if (needGender) {
                profile.setGender(IdCardUtil.parseGenderCode(idNo));
            }
            if (needBirthday) {
                profile.setBirthday(IdCardUtil.parseBirthday(idNo));
            }
            EntityMeta.onUpdate(profile);
            peopleProfileMapper.updateProfile(profile);
        } catch (Exception ignored) {
            // 解密失败不阻断列表/详情；年龄仍显示为 -
        }
    }

    private PeopleProfile newPeople(
            String tenantId, String name, String gender, LocalDate birthday, String mobile, String mobileOwner) {
        quotaService.assertAvailable(tenantId, QuotaKeyEnum.PATIENT_TOTAL, 1);
        PeopleProfile profile = new PeopleProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(null);
        profile.setDisplayName(name);
        profile.setGender(gender);
        profile.setBirthday(birthday);
        profile.setMobile(mobile);
        profile.setMobileOwner(mobileOwner);
        profile.setNamePinyin(IdentityService.toPinyinKey(name));
        profile.setAllergensJson("[]");
        EntityMeta.onCreate(profile);
        peopleProfileMapper.insert(profile);
        return profile;
    }

    private record IdentityPayload(
            PeopleIdentityTypeEnum type, String normalized, String hash, String cipher, String mask, String gender, LocalDate birthday) {
    }

    private IdentityPayload resolveIdentity(
            String tenantId, String identityType, String identityValue, String gender, LocalDate birthday) {
        if (!StringUtils.hasText(identityType) || !StringUtils.hasText(identityValue)) {
            throw new BusinessException(400, "请填写证件类型和证件号码");
        }
        PeopleIdentityTypeEnum type = PeopleIdentityTypeEnum.valueOf(identityType);
        String normalized = identityValue.trim().toUpperCase();
        String resolvedGender = gender;
        LocalDate resolvedBirthday = birthday;
        if (type == PeopleIdentityTypeEnum.ID_CARD) {
            normalized = IdCardUtil.normalize(identityValue);
            if (!IdCardUtil.isValid(normalized)) {
                throw new BusinessException(400, "身份证号无效");
            }
            resolvedGender = IdCardUtil.parseGenderCode(normalized);
            resolvedBirthday = IdCardUtil.parseBirthday(normalized);
        } else {
            if (!StringUtils.hasText(resolvedGender) || resolvedBirthday == null) {
                throw new BusinessException(400, "非身份证建档须填写性别和出生日期");
            }
            if (normalized.length() < 4) {
                throw new BusinessException(400, "证件号码过短");
            }
        }
        return new IdentityPayload(
                type,
                normalized,
                idCardCrypto.hash(tenantId, type.name() + "|" + normalized),
                idCardCrypto.encrypt(type.name() + "|" + normalized),
                IdCardUtil.mask(normalized),
                resolvedGender,
                resolvedBirthday);
    }

    private void insertIdentity(String tenantId, String peopleId, IdentityPayload payload) {
        PeopleIdentity identity = new PeopleIdentity();
        identity.setTenantId(tenantId);
        identity.setPeopleId(peopleId);
        identity.setIdentityType(payload.type().name());
        identity.setIdentityValueHash(payload.hash());
        identity.setIdentityValueCipher(payload.cipher());
        identity.setIdentityValueMask(payload.mask());
        EntityMeta.onCreate(identity);
        peopleIdentityMapper.insert(identity);
    }

    private PatientOrgMembership ensureMembership(String orgId, String tenantId, String peopleId, String staffId) {
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
        return membership;
    }

    private void ensureBasicArchive(String tenantId, String peopleId, String staffId) {
        basicArchiveService.ensureEmpty(tenantId, peopleId, staffId);
    }

    private void ensureCareAssignment(String orgId, String tenantId, String peopleId) {
        PatientCareAssignment assignment = careAssignmentMapper.find(tenantId, peopleId);
        if (assignment == null) {
            assignment = new PatientCareAssignment();
            assignment.setTenantId(tenantId);
            assignment.setPeopleId(peopleId);
            assignment.setPrimaryOrgId(orgId);
            assignment.setPrimaryCareManagerStaffId(null);
            EntityMeta.onCreate(assignment);
            careAssignmentMapper.insert(assignment);
        }
    }

    private void syncCareAssignmentForTeamPatients(CareTeam team) {
        for (CareTeamMember member : careTeamMemberMapper.listByTeam(team.getId())) {
            if (!CareTeamMemberTypeEnum.PATIENT.matches(member.getMemberType())) {
                continue;
            }
            upsertCareAssignment(
                    team.getTenantId(),
                    member.getPeopleId(),
                    team.getOrgId(),
                    team.getPrimaryCareManagerStaffId());
        }
    }

    private void upsertCareAssignment(String tenantId, String patientId, String orgId, String careManagerStaffId) {
        PatientCareAssignment assignment = careAssignmentMapper.find(tenantId, patientId);
        if (assignment == null) {
            assignment = new PatientCareAssignment();
            assignment.setTenantId(tenantId);
            assignment.setPeopleId(patientId);
            assignment.setPrimaryOrgId(orgId);
            assignment.setPrimaryCareManagerStaffId(careManagerStaffId);
            EntityMeta.onCreate(assignment);
            careAssignmentMapper.insert(assignment);
        } else {
            assignment.setPrimaryOrgId(orgId);
            assignment.setPrimaryCareManagerStaffId(careManagerStaffId);
            EntityMeta.onUpdate(assignment);
            careAssignmentMapper.update(assignment);
        }
    }

    private void assertOrgClinicalStaff(String orgId, String staffId, StaffRoleEnum requiredRole) {
        String tenantId = requireTenantId();
        StaffProfile profile = staffProfileMapper.findById(staffId);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(400, "员工不属于当前租户：" + staffId);
        }
        Set<String> roles = staffRoleBindingMapper.listByStaff(staffId).stream()
                .map(StaffRoleBinding::getRoleCode)
                .collect(Collectors.toSet());
        boolean tenantAdmin = roles.contains(StaffRoleEnum.TENANT_ADMIN.name());
        if (!tenantAdmin && staffOrgBindingMapper.countBinding(staffId, orgId) <= 0) {
            throw new BusinessException(400, "员工未绑定该机构：" + staffId);
        }
        boolean clinical = roles.contains(StaffRoleEnum.CARE_MANAGER.name())
                || roles.contains(StaffRoleEnum.DOCTOR.name())
                || tenantAdmin;
        if (!clinical) {
            throw new BusinessException(400, "员工不是健管师或医生：" + staffId);
        }
        if (requiredRole != null && !roles.contains(requiredRole.name())) {
            // 租户管理员可兼任主责健管师，无需再挂 CARE_MANAGER
            if (requiredRole == StaffRoleEnum.CARE_MANAGER && tenantAdmin) {
                return;
            }
            throw new BusinessException(400, "员工缺少角色 " + requiredRole.name());
        }
    }

    private void ensureStaffMember(CareTeam team, String staffId) {
        CareTeamMember existing = careTeamMemberMapper.findStaff(team.getId(), staffId);
        if (existing != null) {
            return;
        }
        careTeamMemberMapper.insert(newStaffMember(team, staffId));
    }

    private CareTeamMember newStaffMember(CareTeam team, String staffId) {
        CareTeamMember member = new CareTeamMember();
        member.setTenantId(team.getTenantId());
        member.setOrgId(team.getOrgId());
        member.setTeamId(team.getId());
        member.setMemberType(CareTeamMemberTypeEnum.STAFF.name());
        member.setStaffId(staffId);
        EntityMeta.onCreate(member);
        member.setJoinedAt(member.getGmtCreated());
        return member;
    }

    private CareTeam requireTeamInOrg(String orgId, String teamId) {
        CareTeam team = careTeamMapper.findById(teamId);
        if (team == null || !orgId.equals(team.getOrgId())) {
            throw new BusinessException(404, "健管组不存在");
        }
        return team;
    }

    private CareTeamListItem toTeamItem(CareTeam team) {
        CareTeamListItem item = new CareTeamListItem();
        item.setId(team.getId());
        item.setTeamCode(team.getTeamCode());
        item.setName(team.getName());
        item.setPrimaryCareManagerStaffId(team.getPrimaryCareManagerStaffId());
        item.setPrimaryDoctorStaffId(team.getPrimaryDoctorStaffId());
        item.setStatus(team.getStatus());
        item.setCreatedAt(team.getGmtCreated());
        item.setMemberCount(careTeamMemberMapper.countActiveByTeam(team.getId()));
        StaffProfile cm = staffProfileMapper.findById(team.getPrimaryCareManagerStaffId());
        if (cm != null) {
            item.setPrimaryCareManagerName(cm.getDisplayName());
        }
        if (team.getPrimaryDoctorStaffId() != null) {
            StaffProfile doc = staffProfileMapper.findById(team.getPrimaryDoctorStaffId());
            if (doc != null) {
                item.setPrimaryDoctorName(doc.getDisplayName());
            }
        }
        return item;
    }

    private Map<String, List<String>> loadRoles(List<String> staffIds) {
        Map<String, List<String>> map = new HashMap<>();
        if (staffIds.isEmpty()) {
            return map;
        }
        for (StaffRoleBinding binding : staffRoleBindingMapper.listByStaffIds(staffIds)) {
            map.computeIfAbsent(binding.getStaffId(), k -> new ArrayList<>()).add(binding.getRoleCode());
        }
        return map;
    }

    private void auditAddMember(String actorAccountId, String teamId, CareTeamMember member) {
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                AuditActionEnum.CARE_TEAM_MEMBER_ADD.name(),
                "care_team_member",
                String.valueOf(member.getId()),
                member.getPeopleId(),
                AuditDetails.of("teamId", teamId, "memberType", member.getMemberType()));
    }

    private static boolean isTenantAdmin() {
        return currentRoles().contains(StaffRoleEnum.TENANT_ADMIN.name());
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return Set.of();
        }
        return new HashSet<>(ctx.getRoles());
    }

    private static String requireTenantId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getTenantId() == null) {
            throw new BusinessException(401, "缺少租户上下文");
        }
        return ctx.getTenantId();
    }

    private static String requireStaffId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getStaffId() == null) {
            throw new BusinessException(401, "缺少员工上下文");
        }
        return ctx.getStaffId();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
