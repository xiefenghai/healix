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
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import com.github.promeg.pinyinhelper.Pinyin;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 跨端账号 / 人员档案查询与注册（Ops / B / C）。
 */
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
    private final AccountPatientMapper accountPatientMapper;
    private final PasswordEncoder passwordEncoder;

    public OpsAccount requireOpsByUsername(String username) {
        OpsAccount account = opsAccountMapper.findByUsername(username);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return account;
    }

    public OpsAccount requireOpsById(String accountId) {
        OpsAccount account = opsAccountMapper.findById(accountId);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "账号不可用");
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

    public List<StaffProfile> listStaffByAccount(String accountId) {
        return staffProfileMapper.listByAccountId(accountId);
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

    /** 用本人登录手机号注册。用户名即规范化后的手机号，平台唯一，不按租户隔离。 */
    @Transactional
    public PeopleAccount registerByMobile(String tenantId, String mobile) {
        return registerByMobileGlobal(mobile, tenantId);
    }

    /** 平台级注册：手机号全局唯一；tenantId 可空，仅兼容旧字段写入。 */
    @Transactional
    public PeopleAccount registerByMobileGlobal(String mobile) {
        return registerByMobileGlobal(mobile, null);
    }

    @Transactional
    public PeopleAccount registerByMobileGlobal(String mobile, String legacyTenantId) {
        if (peopleAccountMapper.findByUsername(mobile) != null) {
            throw new BusinessException(409, "该手机号已注册，请直接登录");
        }
        PeopleAccount account = new PeopleAccount();
        account.setTenantId(StringUtils.hasText(legacyTenantId) ? legacyTenantId : null);
        account.setUsername(mobile);
        account.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        account.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(account);
        peopleAccountMapper.insert(account);
        return account;
    }

    public PeopleAccount requireByMobile(String tenantId, String mobile) {
        return requireByMobileGlobal(mobile);
    }

    public PeopleAccount requireByMobileGlobal(String mobile) {
        PeopleAccount account = peopleAccountMapper.findByUsername(mobile);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "请先注册");
        }
        return account;
    }

    /** 按手机号取账号；没有则静默注册（激活等场景）。 */
    @Transactional
    public PeopleAccount findOrRegisterByMobile(String tenantId, String mobile) {
        PeopleAccount existing = peopleAccountMapper.findByUsername(mobile);
        if (existing != null) {
            if (!EnableStatusEnum.ACTIVE.matches(existing.getStatus())) {
                throw new BusinessException(401, "账号不可用");
            }
            return existing;
        }
        return registerByMobileGlobal(mobile, tenantId);
    }

    public PeopleAccount requirePeopleAccountById(String accountId) {
        PeopleAccount account = peopleAccountMapper.findById(accountId);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "账号不可用");
        }
        return account;
    }

    /** 仅创建 C 端登录账号（不创建 people / 就诊人）。 */
    @Transactional
    public PeopleAccount registerAccount(String tenantId, String username, String password) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(400, "请指定租户");
        }
        if (peopleAccountMapper.findByTenantAndUsername(tenantId, username) != null) {
            throw new BusinessException(409, "用户名已存在");
        }
        PeopleAccount account = new PeopleAccount();
        account.setTenantId(tenantId);
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(account);
        peopleAccountMapper.insert(account);
        return account;
    }



    public PeopleAccount requirePeopleAccount(String tenantId, String username) {
        PeopleAccount account = peopleAccountMapper.findByTenantAndUsername(tenantId, username);
        if (account == null || !EnableStatusEnum.ACTIVE.matches(account.getStatus())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return account;
    }


    public PeopleProfile requirePeople(String peopleId) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException("患者不存在");
        }
        return profile;
    }


    @Transactional
    public PeopleProfile updatePeopleProfile(
            String peopleId, String displayName, String gender, LocalDate birthday, String allergensJson) {
        PeopleProfile profile = requirePeople(peopleId);
        // 部分更新：请求未传的字段保持原值（C 端改性别时常不带 birthday，不能写成 null）
        if (displayName != null) {
            profile.setDisplayName(displayName);
            profile.setNamePinyin(toPinyinKey(displayName));
        }
        if (gender != null) {
            profile.setGender(gender);
        }
        if (birthday != null) {
            profile.setBirthday(birthday);
        }
        if (allergensJson != null) {
            profile.setAllergensJson(allergensJson);
        }
        EntityMeta.onUpdate(profile);
        peopleProfileMapper.updateProfile(profile);
        // 档案姓名为准，同步 C 端就诊人卡片上的冗余 display_name
        if (StringUtils.hasText(profile.getDisplayName())) {
            accountPatientMapper.updateDisplayNameByPeopleId(
                    peopleId, profile.getDisplayName().trim(), LocalDateTime.now());
        }
        return profile;
    }

    public List<PeopleProfile> searchPeople(String tenantId, String keyword, int limit) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : keyword;
        return peopleProfileMapper.searchByTenant(tenantId, kw, limit);
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

    /**
     * 姓名检索键：全拼 + 首字母，空格分隔，如 {@code zhangjianguo zjg}。
     * <p>写入 {@code people_profile.name_pinyin}；检索只读该字段。
     */
    public static String toPinyinKey(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String trimmed = name.trim();
        StringBuilder full = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || isNamePunctuation(cp)) {
                continue;
            }
            if (cp <= 0xFFFF && Pinyin.isChinese((char) cp)) {
                String py = Pinyin.toPinyin((char) cp).toLowerCase(Locale.ROOT);
                full.append(py);
                if (!py.isEmpty()) {
                    initials.append(py.charAt(0));
                }
            } else if (Character.isLetterOrDigit(cp)) {
                int lower = Character.toLowerCase(cp);
                full.appendCodePoint(lower);
                initials.appendCodePoint(lower);
            }
        }
        if (full.isEmpty()) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        String fullKey = full.toString();
        String initialKey = initials.toString();
        if (!StringUtils.hasText(initialKey) || initialKey.equals(fullKey)) {
            return fullKey;
        }
        return fullKey + " " + initialKey;
    }

    /**
     * 关键字是否命中已落库的 {@code name_pinyin}（全拼或首字母）。
     * <p>检索热路径只读库字段，不在内存里即时转拼音；存量靠启动回填写入。
     */
    public static boolean matchesNamePinyin(String storedPinyin, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return false;
        }
        String q = normalizePinyinQuery(keyword);
        if (!StringUtils.hasText(q)) {
            return false;
        }
        return pinyinKeyContains(storedPinyin, q);
    }

    private static String normalizePinyinQuery(String keyword) {
        StringBuilder sb = new StringBuilder();
        String raw = keyword.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || isNamePunctuation(cp)) {
                continue;
            }
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private static boolean pinyinKeyContains(String key, String q) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(q)) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String part : lower.split("\\s+")) {
            if (part.contains(q)) {
                return true;
            }
        }
        return lower.replace(" ", "").contains(q);
    }

    private static boolean isNamePunctuation(int cp) {
        return cp == '·' || cp == '•' || cp == '-' || cp == '_' || cp == '.' || cp == '．';
    }
}
