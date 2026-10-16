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
import com.healix.core.people.enums.PeopleIdentityTypeEnum;
import com.healix.core.people.mapper.PeopleIdentityMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
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
            }
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

    /**
     * C 端自助添加就诊人：姓名 + 性别 + 出生日期即可，证件选填。
     *
     * <p>不按手机号或姓名去匹配机构档案（共用号码会串档）；机构档案只能用激活码关联。
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
        if (resolvedGender == null || resolvedBirthday == null) {
            throw new BusinessException(400, "请填写性别和出生日期");
        }
        if (resolvedBirthday.isAfter(LocalDate.now())) {
            throw new BusinessException(400, "出生日期不能晚于今天");
        }
        String mobileNorm = normalizeMobile(mobile);

        if (bits != null) {
            if (accountPatientMapper.findByAccountAndIdentityHash(accountId, bits.type().name(), bits.hash()) != null) {
                throw new BusinessException(409, "该证件已在您的就诊人列表中");
            }
            if (peopleIdentityMapper.findByTenantTypeHash(tenantId, bits.type().name(), bits.hash()) != null) {
                throw new BusinessException(409, "该就诊人已在机构建档，请使用机构发放的激活码添加");
            }
        }
        if (findSamePersonCard(accountId, name, resolvedBirthday) != null) {
            throw new BusinessException(409, "「" + name + "」已在您的就诊人列表中");
        }

        quotaService.assertAvailable(tenantId, QuotaKeyEnum.PATIENT_TOTAL, 1);
        PeopleProfile profile = new PeopleProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(null);
        profile.setDisplayName(name);
        profile.setNamePinyin(IdentityService.toPinyinKey(name));
        profile.setGender(resolvedGender);
        profile.setBirthday(resolvedBirthday);
        profile.setMobile(mobileNorm);
        profile.setMobileOwner(mobileNorm == null ? null : MobileOwnerEnum.normalize(mobileOwner));
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

    /** 账号内同名同生日的就诊人卡片（视为同一人）。 */
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
        if (!g.equals("MALE") && !g.equals("FEMALE")) {
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
