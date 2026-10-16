package com.healix.core.patientcard.service;

import com.healix.common.exception.BusinessException;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.people.service.PatientMergeService;
import com.healix.core.portal.enums.PortalEnum;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 激活机构档案后，把用户此前自己添加的同一人（同名同生日）的记录并入机构档案。
 *
 * <p>只允许合并「自建、未入组任何机构、仅本账号持有」的档案，避免 C 端改动他人或机构数据。
 */
@Service
@RequiredArgsConstructor
public class PatientCardMergeService {

    private final AccountPatientMapper accountPatientMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final PatientCardService patientCardService;
    private final PatientMergeService patientMergeService;

    /** @param counts 自建档案里已有的记录数（中文名 → 条数），供用户判断是否合并 */
    public record MergeCandidate(
            String sourceCardId, String displayName, LocalDate birthday, Map<String, Integer> counts) {}

    public MergeCandidate findCandidate(String accountId, String targetCardId) {
        AccountPatient target = patientCardService.requireOwnedCard(accountId, targetCardId);
        PeopleProfile targetPeople = peopleProfileMapper.findById(target.getPeopleId());
        if (targetPeople == null || targetPeople.getBirthday() == null) {
            return null;
        }
        for (AccountPatient card : accountPatientMapper.listByAccount(accountId)) {
            if (card.getId().equals(target.getId())) {
                continue;
            }
            PeopleProfile p = peopleProfileMapper.findById(card.getPeopleId());
            if (p != null && samePerson(p, targetPeople) && selfOwned(p, targetPeople)) {
                var preview = patientMergeService.preview(p.getTenantId(), p.getId());
                return new MergeCandidate(card.getId(), p.getDisplayName(), p.getBirthday(), preview.counts());
            }
        }
        return null;
    }

    @Transactional
    public void merge(String accountId, String targetCardId, String sourceCardId) {
        AccountPatient target = patientCardService.requireOwnedCard(accountId, targetCardId);
        AccountPatient source = patientCardService.requireOwnedCard(accountId, sourceCardId);
        PeopleProfile targetPeople = peopleProfileMapper.findById(target.getPeopleId());
        PeopleProfile sourcePeople = peopleProfileMapper.findById(source.getPeopleId());
        if (targetPeople == null || sourcePeople == null || !samePerson(sourcePeople, targetPeople)) {
            throw new BusinessException(400, "两位就诊人姓名或出生日期不一致，不能合并");
        }
        if (!selfOwned(sourcePeople, targetPeople)) {
            throw new BusinessException(409, "该就诊人记录已关联机构或其他账号，请联系健管师处理");
        }
        accountPatientMapper.softDelete(source.getId(), LocalDateTime.now());
        patientMergeService.merge(
                targetPeople.getTenantId(),
                sourcePeople.getId(),
                targetPeople.getId(),
                accountId,
                "PEOPLE",
                PortalEnum.C.code(),
                "激活机构档案后，用户合并自己添加的同一人记录");
    }

    private static boolean samePerson(PeopleProfile a, PeopleProfile b) {
        return !a.getId().equals(b.getId())
                && a.getBirthday() != null
                && a.getBirthday().equals(b.getBirthday())
                && StringUtils.hasText(a.getDisplayName())
                && a.getDisplayName().trim().equals(b.getDisplayName() == null ? null : b.getDisplayName().trim());
    }

    private boolean selfOwned(PeopleProfile source, PeopleProfile target) {
        return Objects.equals(source.getTenantId(), target.getTenantId())
                && membershipMapper.listActiveByPeople(source.getId()).isEmpty()
                && accountPatientMapper.countByPeople(source.getId()) == 1;
    }
}
