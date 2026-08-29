package com.healix.core.identity.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.identity.domain.OpsAccount;
import com.healix.core.identity.domain.StaffAccount;
import com.healix.core.identity.domain.StaffOrgBinding;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.domain.StaffRoleBinding;
import com.healix.core.identity.enums.EnableStatusEnum;
import com.healix.core.identity.enums.OpsRoleEnum;
import com.healix.core.identity.mapper.OpsAccountMapper;
import com.healix.core.identity.mapper.StaffAccountMapper;
import com.healix.core.identity.mapper.StaffOrgBindingMapper;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.identity.mapper.StaffRoleBindingMapper;
import com.healix.core.people.domain.PeopleAccount;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleAccountMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class IdentityService {

    private final OpsAccountMapper opsAccountMapper;
    private final StaffAccountMapper staffAccountMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final StaffOrgBindingMapper staffOrgBindingMapper;
    private final StaffRoleBindingMapper staffRoleBindingMapper;
    private final PeopleAccountMapper peopleAccountMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final PasswordEncoder passwordEncoder;

    public OpsAccount requireOpsByUsername(String username) {
        OpsAccount account = opsAccountMapper.findByUsername(username);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return account;
    }

    public boolean matches(String raw, String hash) {
        return passwordEncoder.matches(raw, hash);
    }

    public StaffProfile requireStaffByAccount(String accountId) {
        StaffProfile profile = staffProfileMapper.findByAccountId(accountId);
        if (profile == null) {
            throw new BusinessException("员工资料不存在");
        }
        return profile;
    }

    public StaffProfile requireStaff(String staffId) {
        StaffProfile profile = staffProfileMapper.findById(staffId);
        if (profile == null) {
            throw new BusinessException("员工资料不存在");
        }
        return profile;
    }

    public StaffAccount requireStaffAccount(String username) {
        StaffAccount account = staffAccountMapper.findByUsername(username);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return account;
    }

    public Set<String> staffRoles(String staffId) {
        return staffRoleBindingMapper.listByStaff(staffId).stream()
                .map(StaffRoleBinding::getRoleCode)
                .collect(Collectors.toSet());
    }

    public List<StaffOrgBinding> staffOrgs(String staffId) {
        return staffOrgBindingMapper.listByStaff(staffId);
    }

    public void assertStaffInOrg(String staffId, String orgId) {
        if (staffOrgBindingMapper.countBinding(staffId, orgId) <= 0) {
            throw new BusinessException("员工未绑定该机构");
        }
    }

    @Transactional
    public PeopleProfile registerPeople(String tenantId, String username, String password, String displayName) {
        if (tenantId == null) {
            throw new BusinessException(400, "请指定租户");
        }
        if (peopleAccountMapper.findByTenantAndUsername(tenantId, username) != null) {
            throw new BusinessException("用户名已存在");
        }
        PeopleAccount account = new PeopleAccount();
        account.setTenantId(tenantId);
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(account);
        peopleAccountMapper.insert(account);

        PeopleProfile profile = new PeopleProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(account.getId());
        profile.setDisplayName(displayName != null ? displayName : username);
        profile.setNamePinyin(toPinyinKey(profile.getDisplayName()));
        profile.setAllergensJson("[]");
        EntityMeta.onCreate(profile);
        peopleProfileMapper.insert(profile);
        return profile;
    }

    public PeopleAccount requirePeopleAccount(String tenantId, String username) {
        PeopleAccount account = peopleAccountMapper.findByTenantAndUsername(tenantId, username);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return account;
    }

    public PeopleProfile requirePeopleByAccount(String accountId) {
        PeopleProfile profile = peopleProfileMapper.findByAccountId(accountId);
        if (profile == null) {
            throw new BusinessException("患者资料不存在");
        }
        return profile;
    }

    public PeopleProfile requirePeople(String peopleId) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException("患者不存在");
        }
        return profile;
    }

    /** @deprecated use requirePeople */
    @Deprecated
    public PeopleProfile requirePatient(String patientId) {
        return requirePeople(patientId);
    }

    @Transactional
    public PeopleProfile updatePeopleProfile(
            String peopleId, String displayName, String gender, LocalDate birthday, String allergensJson) {
        PeopleProfile profile = requirePeople(peopleId);
        profile.setDisplayName(displayName);
        profile.setNamePinyin(toPinyinKey(displayName));
        profile.setGender(gender);
        profile.setBirthday(birthday);
        profile.setAllergensJson(allergensJson != null ? allergensJson : profile.getAllergensJson());
        EntityMeta.onUpdate(profile);
        peopleProfileMapper.updateProfile(profile);
        return profile;
    }

    public List<PeopleProfile> searchPeople(String tenantId, String keyword, int limit) {
        return peopleProfileMapper.searchByTenant(tenantId, keyword, limit);
    }

    /** @deprecated use searchPeople */
    @Deprecated
    public List<PeopleProfile> searchPatients(String tenantId, String keyword, int limit) {
        return searchPeople(tenantId, keyword, limit);
    }

    public List<String> peopleAllergens(String peopleId) {
        PeopleProfile profile = requirePeople(peopleId);
        if (profile.getAllergensJson() == null || profile.getAllergensJson().isBlank()) {
            return List.of();
        }
        String raw = profile.getAllergensJson().replace("[", "").replace("]", "").replace("\"", "");
        if (raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** @deprecated use peopleAllergens */
    @Deprecated
    public List<String> patientAllergens(String patientId) {
        return peopleAllergens(patientId);
    }

    public int opsAccountCount() {
        return opsAccountMapper.countAll();
    }

    @Transactional
    public void ensureBootstrapOps(String username, String password) {
        if (opsAccountMapper.countAll() > 0) {
            return;
        }
        OpsAccount account = new OpsAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setDisplayName("Super Admin");
        account.setRoleCode(OpsRoleEnum.SUPER_ADMIN.name());
        account.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(account);
        opsAccountMapper.insert(account);
    }

    public static String toPinyinKey(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
