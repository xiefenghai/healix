package com.healix.core.patientcard.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.IdCardCrypto;
import com.healix.common.util.IdCardUtil;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.govern.service.QuotaService;
import com.healix.core.identity.service.IdentityService;
import com.healix.core.people.domain.PeopleIdentity;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.enums.MobileOwnerEnum;
import com.healix.core.people.support.MobileNumbers;
import com.healix.core.people.enums.PeopleIdentityTypeEnum;
import com.healix.core.people.mapper.PeopleIdentityMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PatientCardService {

    public static final int MAX_CARDS_PER_ACCOUNT = 10;

    private final AccountPatientMapper accountPatientMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleIdentityMapper peopleIdentityMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final IdCardCrypto idCardCrypto;
    private final QuotaService quotaService;

    public List<AccountPatient> listByAccount(String accountId) {
        List<AccountPatient> cards = accountPatientMapper.listByAccount(accountId);
        // 档案姓名为准，避免卡片冗余名与管理端不一致
        for (AccountPatient card : cards) {
            if (!StringUtils.hasText(card.getPeopleId())) {
                continue;
            }
            PeopleProfile profile = peopleProfileMapper.findById(card.getPeopleId());
            if (profile != null && StringUtils.hasText(profile.getDisplayName())) {
                card.setDisplayName(profile.getDisplayName().trim());
            }
            if (profile != null) {
                card.setGender(profile.getGender());
                card.setBirthday(profile.getBirthday());
                if (!StringUtils.hasText(card.getMobile()) && StringUtils.hasText(profile.getMobile())) {
                    card.setMobile(profile.getMobile());
                }
            }
            if (!StringUtils.hasText(card.getIdentityValueMask())) {
                PeopleIdentity primary = peopleIdentityMapper.findPrimaryMask(card.getPeopleId());
                if (primary != null) {
                    card.setIdentityType(primary.getIdentityType());
                    card.setIdentityValueMask(primary.getIdentityValueMask());
                }
            }
            List<PatientOrgMembership> memberships = membershipMapper.listActiveByPeople(card.getPeopleId());
            card.setInCare(memberships != null && !memberships.isEmpty());
        }
        return cards;
    }

    public AccountPatient requireOwnedCard(String accountId, String cardId) {
        AccountPatient card = accountPatientMapper.findById(cardId);
        if (card == null || !accountId.equals(card.getAccountId())) {
            throw new BusinessException(404, "就诊人不存在");
        }
        return card;
    }

    public AccountPatient findFirstCard(String accountId) {
        List<AccountPatient> cards = accountPatientMapper.listByAccount(accountId);
        return cards.isEmpty() ? null : cards.get(0);
    }

    public int countLinkedAccounts(String peopleId) {
        return accountPatientMapper.countByPeople(peopleId);
    }

    /** 证件已登记时返回所属 peopleId，没有则 null。 */
    public String findIdentityOwnerPeopleId(String tenantId, String identityType, String identityValue) {
        if (!StringUtils.hasText(identityValue)) {
            return null;
        }
        IdentityBits bits = resolveIdentity(
                tenantId, StringUtils.hasText(identityType) ? identityType : "ID_CARD", identityValue);
        PeopleIdentity existing =
                peopleIdentityMapper.findByTenantTypeHash(tenantId, bits.type().name(), bits.hash());
        return existing == null ? null : existing.getPeopleId();
    }

    /**
     * C 端自助添加就诊人：新建 people（调用方已按手机号+姓名+生日完成认档决策）；证件选填。
     */
    @Transactional
    public AccountPatient createSelfServe(
            String tenantId,
            String accountId,
            String displayName,
            String gender,
            LocalDate birthday,
            String identityType,
            String identityValue,
            String relation,
            String mobile,
            String mobileOwner) {
        if (!StringUtils.hasText(displayName)) {
            throw new BusinessException(400, "请填写就诊人姓名");
        }
        String name = displayName.trim();
        if (name.length() > 64) {
            throw new BusinessException(400, "姓名过长");
        }
        if (accountPatientMapper.countByAccount(accountId) >= MAX_CARDS_PER_ACCOUNT) {
            throw new BusinessException(409, "就诊人数量已达上限");
        }
        IdentityBits bits = StringUtils.hasText(identityValue)
                ? resolveIdentity(tenantId, StringUtils.hasText(identityType) ? identityType : "ID_CARD", identityValue)
                : null;
        String resolvedGender = bits != null && bits.gender() != null ? bits.gender() : normalizeGender(gender);
        LocalDate resolvedBirthday = bits != null && bits.birthday() != null ? bits.birthday() : birthday;
        if (resolvedBirthday != null && resolvedBirthday.isAfter(LocalDate.now())) {
            throw new BusinessException(400, "出生日期不能晚于今天");
        }
        String mobileNorm = MobileNumbers.requireSelf(mobile);
        if (!"SELF".equals(MobileOwnerEnum.require(mobileOwner))) {
            throw new BusinessException(400, "请填写本人手机号");
        }

        if (findSamePersonCardByMobileName(accountId, mobileNorm, name) != null) {
            throw new BusinessException(409, "「" + name + "」已在您的就诊人列表中");
        }
        if (bits != null
                && accountPatientMapper.findByAccountAndIdentityHash(accountId, bits.type().name(), bits.hash())
                        != null) {
            throw new BusinessException(409, "该证件已在您的就诊人列表中");
        }

        if (StringUtils.hasText(tenantId)) {
            quotaService.assertAvailable(tenantId, QuotaKeyEnum.PATIENT_TOTAL, 1);
        }
        PeopleProfile profile = new PeopleProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(null);
        profile.setDisplayName(name);
        profile.setNamePinyin(IdentityService.toPinyinKey(name));
        profile.setGender(resolvedGender);
        profile.setBirthday(resolvedBirthday);
        profile.setMobile(mobileNorm);
        profile.setMobileOwner(MobileOwnerEnum.normalize(mobileOwner));
        profile.setAllergensJson("[]");
        EntityMeta.onCreate(profile);
        peopleProfileMapper.insert(profile);

        if (bits != null) {
            PeopleIdentity identity = new PeopleIdentity();
            identity.setTenantId(tenantId);
            identity.setPeopleId(profile.getId());
            identity.setIdentityType(bits.type().name());
            identity.setIdentityValueHash(bits.hash());
            identity.setIdentityValueCipher(bits.cipher());
            identity.setIdentityValueMask(bits.mask());
            EntityMeta.onCreate(identity);
            peopleIdentityMapper.insert(identity);
        }

        return insertCard(
                tenantId,
                accountId,
                profile.getId(),
                name,
                relation,
                bits == null ? null : bits.type().name(),
                bits == null ? null : bits.hash(),
                bits == null ? null : bits.mask(),
                mobileNorm);
    }

    /** 账号内同名同生日的就诊人卡片（历史兼容）。 */
    public AccountPatient findSamePersonCard(String accountId, String name, LocalDate birthday) {
        if (!StringUtils.hasText(name) || birthday == null) {
            return null;
        }
        for (AccountPatient card : accountPatientMapper.listByAccount(accountId)) {
            PeopleProfile p = peopleProfileMapper.findById(card.getPeopleId());
            if (p != null && name.trim().equals(p.getDisplayName().trim()) && birthday.equals(p.getBirthday())) {
                return card;
            }
        }
        return null;
    }

    /** 账号内对齐键：本人手机号 + 姓名。 */
    public AccountPatient findSamePersonCardByMobileName(String accountId, String mobile, String name) {
        if (!StringUtils.hasText(mobile) || !StringUtils.hasText(name)) {
            return null;
        }
        String n = name.trim();
        for (AccountPatient card : accountPatientMapper.listByAccount(accountId)) {
            PeopleProfile p = peopleProfileMapper.findById(card.getPeopleId());
            if (p != null
                    && MobileOwnerEnum.SELF.name().equals(p.getMobileOwner())
                    && mobile.equals(p.getMobile())
                    && MobileNumbers.sameName(n, p.getDisplayName())) {
                return card;
            }
        }
        return null;
    }

    @Transactional
    public AccountPatient linkExistingPeople(
            String tenantId,
            String accountId,
            PeopleProfile people,
            String relation,
            String overrideDisplayName) {
        if (accountPatientMapper.countByAccount(accountId) >= MAX_CARDS_PER_ACCOUNT) {
            throw new BusinessException(409, "就诊人数量已达上限");
        }
        AccountPatient existing =
                accountPatientMapper.findByAccountAndPeople(accountId, people.getId());
        if (existing != null) {
            return existing;
        }

        PeopleIdentity primary = peopleIdentityMapper.findPrimaryMask(people.getId());
        String identityType = primary != null ? primary.getIdentityType() : null;
        String identityHash = primary != null ? primary.getIdentityValueHash() : null;
        String identityMask = primary != null ? primary.getIdentityValueMask() : null;
        if (identityType != null && identityHash != null) {
            AccountPatient byId = accountPatientMapper.findByAccountAndIdentityHash(
                    accountId, identityType, identityHash);
            if (byId != null) {
                throw new BusinessException(409, "该证件已在您的就诊人列表中");
            }
        }

        String name = StringUtils.hasText(overrideDisplayName)
                ? overrideDisplayName.trim()
                : people.getDisplayName();
        return insertCard(
                tenantId,
                accountId,
                people.getId(),
                name,
                relation,
                identityType,
                identityHash,
                identityMask,
                null);
    }

    @Transactional
    public void unlink(String accountId, String cardId) {
        AccountPatient card = requireOwnedCard(accountId, cardId);
        LocalDateTime now = LocalDateTime.now();
        accountPatientMapper.softDelete(card.getId(), now);
    }

    /**
     * C 端编辑家庭成员：姓名/关系可改；自用态可改性别生日；在管态性别生日只读；
     * 证件仅允许补填（已有证件不可改）；手机号不可改。
     */
    @Transactional
    public AccountPatient updateOwnedCard(
            String accountId,
            String cardId,
            String displayName,
            String relation,
            String gender,
            LocalDate birthday,
            String identityType,
            String identityValue) {
        AccountPatient card = requireOwnedCard(accountId, cardId);
        PeopleProfile profile = peopleProfileMapper.findById(card.getPeopleId());
        if (profile == null) {
            throw new BusinessException(404, "就诊人档案不存在");
        }
        if (!StringUtils.hasText(displayName)) {
            throw new BusinessException(400, "请填写姓名");
        }
        String name = displayName.trim();
        if (name.length() > 64) {
            throw new BusinessException(400, "姓名过长");
        }
        String relationNorm = normalizeRelation(relation);
        if (!StringUtils.hasText(relationNorm)) {
            throw new BusinessException(400, "请选择关系");
        }
        // 账号下仅允许一个「本人」
        if ("SELF".equals(relationNorm)) {
            for (AccountPatient other : accountPatientMapper.listByAccount(accountId)) {
                if (!card.getId().equals(other.getId()) && "SELF".equalsIgnoreCase(other.getRelation())) {
                    throw new BusinessException(409, "已添加过本人档案");
                }
            }
        }

        boolean inCare = !membershipMapper.listActiveByPeople(card.getPeopleId()).isEmpty();
        PeopleIdentity primary = peopleIdentityMapper.findPrimaryMask(card.getPeopleId());
        boolean hasIdentity = primary != null || StringUtils.hasText(card.getIdentityValueMask());

        IdentityBits bits = null;
        if (!hasIdentity && StringUtils.hasText(identityValue)) {
            String tenantId = StringUtils.hasText(card.getTenantId()) ? card.getTenantId() : profile.getTenantId();
            bits = resolveIdentity(
                    StringUtils.hasText(tenantId) ? tenantId : "platform",
                    StringUtils.hasText(identityType) ? identityType : "ID_CARD",
                    identityValue);
            if (peopleIdentityMapper.findByTenantTypeHash(
                            StringUtils.hasText(tenantId) ? tenantId : "platform",
                            bits.type().name(),
                            bits.hash())
                    != null) {
                throw new BusinessException(409, "该证件号已关联其他档案");
            }
        }

        profile.setDisplayName(name);
        if (!inCare) {
            String genderNorm = bits != null && bits.gender() != null
                    ? bits.gender()
                    : normalizeGender(gender);
            LocalDate birthdayNorm = bits != null && bits.birthday() != null ? bits.birthday() : birthday;
            if (birthdayNorm != null && birthdayNorm.isAfter(LocalDate.now())) {
                throw new BusinessException(400, "出生日期不能晚于今天");
            }
            if (genderNorm != null) {
                profile.setGender(genderNorm);
            }
            if (birthdayNorm != null) {
                profile.setBirthday(birthdayNorm);
            }
        }
        EntityMeta.onUpdate(profile);
        peopleProfileMapper.updateProfile(profile);

        if (bits != null) {
            String tenantId = StringUtils.hasText(card.getTenantId()) ? card.getTenantId() : profile.getTenantId();
            PeopleIdentity identity = new PeopleIdentity();
            identity.setTenantId(StringUtils.hasText(tenantId) ? tenantId : "platform");
            identity.setPeopleId(profile.getId());
            identity.setIdentityType(bits.type().name());
            identity.setIdentityValueHash(bits.hash());
            identity.setIdentityValueCipher(bits.cipher());
            identity.setIdentityValueMask(bits.mask());
            EntityMeta.onCreate(identity);
            peopleIdentityMapper.insert(identity);
            card.setIdentityType(bits.type().name());
            card.setIdentityValueHash(bits.hash());
            card.setIdentityValueMask(bits.mask());
        }

        LocalDateTime now = LocalDateTime.now();
        accountPatientMapper.updateCardMeta(
                card.getId(),
                name,
                relationNorm,
                card.getIdentityType(),
                card.getIdentityValueHash(),
                card.getIdentityValueMask(),
                now);
        card.setDisplayName(name);
        card.setRelation(relationNorm);
        card.setGender(profile.getGender());
        card.setBirthday(profile.getBirthday());
        card.setInCare(inCare);
        if (!StringUtils.hasText(card.getMobile()) && StringUtils.hasText(profile.getMobile())) {
            card.setMobile(profile.getMobile());
        }
        return card;
    }

    private AccountPatient insertCard(
            String tenantId,
            String accountId,
            String peopleId,
            String displayName,
            String relation,
            String identityType,
            String identityHash,
            String identityMask,
            String mobile) {
        AccountPatient card = new AccountPatient();
        card.setTenantId(tenantId);
        card.setAccountId(accountId);
        card.setPeopleId(peopleId);
        card.setDisplayName(displayName);
        card.setRelation(normalizeRelation(relation));
        card.setIdentityType(identityType);
        card.setIdentityValueHash(identityHash);
        card.setIdentityValueMask(identityMask);
        card.setMobile(StringUtils.hasText(mobile) ? mobile.trim() : null);
        EntityMeta.onCreate(card);
        accountPatientMapper.insert(card);
        return card;
    }

    private IdentityBits resolveIdentity(String tenantId, String identityType, String identityValue) {
        PeopleIdentityTypeEnum type;
        try {
            type = PeopleIdentityTypeEnum.valueOf(identityType.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException(400, "证件类型无效");
        }
        String normalized = IdCardUtil.normalize(identityValue);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(400, "请填写证件号码");
        }
        String gender = null;
        java.time.LocalDate birthday = null;
        if (type == PeopleIdentityTypeEnum.ID_CARD) {
            if (!IdCardUtil.isValid(normalized)) {
                throw new BusinessException(400, "身份证号无效");
            }
            gender = IdCardUtil.parseGenderCode(normalized);
            birthday = IdCardUtil.parseBirthday(normalized);
        }
        String material = type.name() + "|" + normalized;
        return new IdentityBits(
                type,
                idCardCrypto.hash(tenantId, material),
                idCardCrypto.encrypt(material),
                IdCardUtil.mask(normalized),
                gender,
                birthday);
    }

    private static String normalizeGender(String gender) {
        if (!StringUtils.hasText(gender)) {
            return null;
        }
        String g = gender.trim().toUpperCase(Locale.ROOT);
        if (!g.equals("MALE") && !g.equals("FEMALE") && !g.equals("UNKNOWN")) {
            throw new BusinessException(400, "性别不正确");
        }
        return g;
    }

    private static String normalizeMobile(String mobile) {
        if (!StringUtils.hasText(mobile)) {
            return null;
        }
        String m = mobile.trim();
        if (m.length() > 32 || !m.matches("^[0-9+\\-\\s]{6,20}$")) {
            throw new BusinessException(400, "联系电话格式不正确");
        }
        return m;
    }

    private static String normalizeRelation(String relation) {
        if (!StringUtils.hasText(relation)) {
            return null;
        }
        return relation.trim().toUpperCase(Locale.ROOT);
    }

    private record IdentityBits(
            PeopleIdentityTypeEnum type,
            String hash,
            String cipher,
            String mask,
            String gender,
            java.time.LocalDate birthday) {}
}
