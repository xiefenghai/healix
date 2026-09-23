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
import com.healix.core.people.enums.PeopleIdentityTypeEnum;
import com.healix.core.people.mapper.PeopleIdentityMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
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

    @Transactional
    public AccountPatient createSelfServe(
            String tenantId,
            String accountId,
            String displayName,
            String identityType,
            String identityValue,
            String relation,
            String mobile) {
        if (!StringUtils.hasText(displayName)) {
            throw new BusinessException(400, "请填写就诊人姓名");
        }
        if (!StringUtils.hasText(identityType) || !StringUtils.hasText(identityValue)) {
            throw new BusinessException(400, "请填写证件信息");
        }
        if (accountPatientMapper.countByAccount(accountId) >= MAX_CARDS_PER_ACCOUNT) {
            throw new BusinessException(409, "就诊人数量已达上限");
        }

        IdentityBits bits = resolveIdentity(tenantId, identityType, identityValue);

        AccountPatient existingCard = accountPatientMapper.findByAccountAndIdentityHash(
                accountId, bits.type().name(), bits.hash());
        if (existingCard != null) {
            throw new BusinessException(409, "该证件已在您的就诊人列表中");
        }

        PeopleIdentity occupied =
                peopleIdentityMapper.findByTenantTypeHash(tenantId, bits.type().name(), bits.hash());
        if (occupied != null) {
            throw new BusinessException(409, "该就诊人已在机构建档，请使用激活码添加");
        }

        quotaService.assertAvailable(tenantId, QuotaKeyEnum.PATIENT_TOTAL, 1);
        PeopleProfile profile = new PeopleProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(null);
        profile.setDisplayName(displayName.trim());
        profile.setNamePinyin(IdentityService.toPinyinKey(profile.getDisplayName()));
        profile.setGender(bits.gender());
        profile.setBirthday(bits.birthday());
        profile.setAllergensJson("[]");
        EntityMeta.onCreate(profile);
        peopleProfileMapper.insert(profile);

        PeopleIdentity identity = new PeopleIdentity();
        identity.setTenantId(tenantId);
        identity.setPeopleId(profile.getId());
        identity.setIdentityType(bits.type().name());
        identity.setIdentityValueHash(bits.hash());
        identity.setIdentityValueCipher(bits.cipher());
        identity.setIdentityValueMask(bits.mask());
        EntityMeta.onCreate(identity);
        peopleIdentityMapper.insert(identity);

        return insertCard(
                tenantId,
                accountId,
                profile.getId(),
                profile.getDisplayName(),
                relation,
                bits.type().name(),
                bits.hash(),
                bits.mask(),
                mobile);
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
