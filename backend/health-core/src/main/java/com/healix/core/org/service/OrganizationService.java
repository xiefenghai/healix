package com.healix.core.org.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.SnowflakeId;
import com.healix.core.identity.enums.EnableStatusEnum;
import com.healix.core.org.domain.OrgInviteCode;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.enums.OrgTypeEnum;
import com.healix.core.org.mapper.OrgInviteCodeMapper;
import com.healix.core.org.mapper.OrganizationMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();

    private final OrganizationMapper organizationMapper;
    private final OrgInviteCodeMapper orgInviteCodeMapper;

    public Organization require(String orgId) {
        Organization org = organizationMapper.findById(orgId);
        if (org == null) {
            throw new BusinessException("机构不存在");
        }
        return org;
    }

    public List<Organization> listByTenant(String tenantId) {
        return organizationMapper.listByTenant(tenantId);
    }

    @Transactional
    public Organization create(String tenantId, String name, String orgType) {
        Organization org = new Organization();
        org.setTenantId(tenantId);
        org.setName(name);
        org.setOrgCode(SnowflakeId.nextOrgCode());
        org.setOrgType(OrgTypeEnum.fromOrDefault(orgType).name());
        org.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(org);
        organizationMapper.insert(org);
        return org;
    }

    @Transactional
    public Organization update(String orgId, String name, String orgType, String status) {
        Organization org = require(orgId);
        if (name == null || name.isBlank()) {
            throw new BusinessException("请填写机构名称");
        }
        org.setName(name.trim());
        org.setOrgType(OrgTypeEnum.fromOrDefault(orgType).name());
        if (status != null && !status.isBlank()) {
            org.setStatus(EnableStatusEnum.valueOf(status.trim()).name());
        }
        EntityMeta.onUpdate(org);
        organizationMapper.update(org);
        return org;
    }

    @Transactional
    public OrgInviteCode createInvite(String tenantId, String orgId, String staffId, int validDays) {
        require(orgId);
        OrgInviteCode invite = new OrgInviteCode();
        invite.setTenantId(tenantId);
        invite.setOrgId(orgId);
        invite.setCode(generateCode());
        invite.setEnabled(1);
        invite.setCreatedByStaffId(staffId);
        EntityMeta.onCreate(invite);
        invite.setExpireAt(invite.getGmtCreated().plusDays(validDays));
        orgInviteCodeMapper.insert(invite);
        return invite;
    }


    private String generateCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        }
        return sb.toString();
    }
}
