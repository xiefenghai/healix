package com.healix.core.patientcard.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.domain.PeopleLinkRequest;
import com.healix.core.patientcard.mapper.PeopleLinkRequestMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.enums.MobileOwnerEnum;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.people.service.PeopleMobileStateService;
import com.healix.core.people.support.MobileNumbers;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 就诊人认档：对齐键为「本人手机号 + 姓名」；C 端添加必须 OTP 验证该手机号。
 * <p>同号同名 → 复用 people；同号异名 → 视为不同人可新建。
 */
@Service
@RequiredArgsConstructor
public class PeopleLinkService {

    public static final String PENDING_MESSAGE = "这个号码已有档案，姓名或出生日期不一致，已交给健康管理团队核对";

    public record CardSubmitResult(String outcome, AccountPatient card, String message, String requestId) {}

    public record LinkRequestView(String id, String status, String displayName, String mobileTail, String message) {}

    private final MobileOtpService mobileOtpService;
    private final PatientCardService patientCardService;
    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleLinkRequestMapper requestMapper;
    private final PeopleMobileStateService mobileStateService;
    private final WorkspaceTaskMapper taskMapper;

    @Transactional
    public CardSubmitResult submitCard(
            String tenantId,
            String accountId,
            String displayName,
            String gender,
            LocalDate birthday,
            String relation,
            String mobile,
            String otpCode,
            String identityType,
            String identityValue) {
        String self = MobileNumbers.requireSelf(mobile);
        String name = requireName(displayName);
        // 对齐键：手机号 + 姓名；OTP 强制验证该号在手上（含登录号本人）
        String otpScope = StringUtils.hasText(tenantId) ? tenantId : "platform";
        mobileOtpService.verify(otpScope, accountId, self, otpCode);

        if (patientCardService.findSamePersonCardByMobileName(accountId, self, name) != null) {
            throw new BusinessException(409, "「" + name + "」已在您的就诊人列表中");
        }

        PeopleProfile target = peopleProfileMapper.findBySelfMobileAndName(self, name);
        if (target != null) {
            rememberOptionalDemographics(target, gender, birthday);
            ensureSelfMobile(target, self);
            if (alreadyLinked(accountId, target.getId())) {
                throw new BusinessException(409, "「" + target.getDisplayName() + "」已在您的就诊人列表中");
            }
            String cardTenant = StringUtils.hasText(target.getTenantId()) ? target.getTenantId() : tenantId;
            AccountPatient card =
                    patientCardService.linkExistingPeople(cardTenant, accountId, target, relation, null);
            return new CardSubmitResult("LINKED", card, null, null);
        }

        String genderNorm = optionalGender(gender);
        LocalDate birthdayNorm = birthday;
        if (birthdayNorm != null && birthdayNorm.isAfter(LocalDate.now())) {
            throw new BusinessException(400, "出生日期不能晚于今天");
        }
        // 性别/生日仍写入档案展示，但不参与对齐；缺省时用占位以便兼容旧表约束路径
        if (genderNorm == null) {
            genderNorm = "UNKNOWN";
        }
        if (birthdayNorm == null) {
            birthdayNorm = LocalDate.of(1970, 1, 1);
        }
        AccountPatient card = patientCardService.createSelfServe(
                tenantId,
                accountId,
                name,
                genderNorm,
                birthdayNorm,
                identityType,
                identityValue,
                relation,
                self,
                MobileOwnerEnum.SELF.name());
        return new CardSubmitResult("CREATED", card, null, null);
    }

    public List<LinkRequestView> listRequests(String accountId) {
        return requestMapper.listVisible(accountId).stream()
                .map(row -> new LinkRequestView(
                        row.getId(),
                        row.getStatus(),
                        row.getDisplayName(),
                        MobileNumbers.tail(row.getMobile()),
                        PeopleLinkRequest.REJECTED.equals(row.getStatus())
                                ? "未能关联，请核对号码或联系健康管理团队"
                                : PENDING_MESSAGE))
                .toList();
    }

    @Transactional
    public void withdraw(String accountId, String requestId) {
        PeopleLinkRequest row = requireOpen(requestId);
        if (!accountId.equals(row.getAccountId())) {
            throw new BusinessException(404, "申请不存在");
        }
        closeRequest(row, PeopleLinkRequest.WITHDRAWN);
        cancelTask(row);
    }

    @Transactional
    public void decide(String requestId, boolean approve) {
        PeopleLinkRequest row = requireOpen(requestId);
        if (row.getExpireAt() != null && row.getExpireAt().isBefore(LocalDateTime.now())) {
            closeRequest(row, PeopleLinkRequest.EXPIRED);
            cancelTask(row);
            throw new BusinessException(409, "申请已过期，请让患者重新提交");
        }
        PeopleProfile target = peopleProfileMapper.findById(row.getTargetPeopleId());
        if (!approve) {
            closeRequest(row, PeopleLinkRequest.REJECTED);
            return;
        }
        if (target == null
                || !row.getMobile().equals(target.getMobile())
                || !MobileOwnerEnum.SELF.name().equals(target.getMobileOwner())) {
            closeRequest(row, PeopleLinkRequest.EXPIRED);
            cancelTask(row);
            throw new BusinessException(409, "号码已经变化，请让患者重新提交");
        }
        rememberOptionalDemographics(target, row.getGender(), row.getBirthday());
        if (!alreadyLinked(row.getAccountId(), target.getId())) {
            patientCardService.linkExistingPeople(
                    row.getTenantId(), row.getAccountId(), target, row.getRelation(), null);
        }
        closeRequest(row, PeopleLinkRequest.APPROVED);
    }

    private void rememberOptionalDemographics(PeopleProfile profile, String gender, LocalDate birthday) {
        boolean dirty = false;
        String g = optionalGender(gender);
        if (g != null && !StringUtils.hasText(profile.getGender())) {
            profile.setGender(g);
            dirty = true;
        } else if (g != null
                && StringUtils.hasText(profile.getGender())
                && !g.equals(profile.getGender())
                && !"UNKNOWN".equals(g)) {
            mobileStateService.putReview(profile.getId(), profile.getTenantId(), "gender", g);
        }
        if (birthday != null && profile.getBirthday() == null) {
            profile.setBirthday(birthday);
            dirty = true;
        }
        if (dirty) {
            EntityMeta.onUpdate(profile);
            peopleProfileMapper.updateProfile(profile);
        }
    }

    private void ensureSelfMobile(PeopleProfile profile, String self) {
        if (self.equals(profile.getMobile()) && MobileOwnerEnum.SELF.name().equals(profile.getMobileOwner())) {
            mobileStateService.setNoSelf(profile.getId(), profile.getTenantId(), false);
            return;
        }
        if (!StringUtils.hasText(profile.getMobile())) {
            profile.setMobile(self);
            profile.setMobileOwner(MobileOwnerEnum.SELF.name());
            EntityMeta.onUpdate(profile);
            peopleProfileMapper.updateProfile(profile);
            mobileStateService.setNoSelf(profile.getId(), profile.getTenantId(), false);
        }
    }

    private boolean alreadyLinked(String accountId, String peopleId) {
        return patientCardService.listByAccount(accountId).stream()
                .anyMatch(card -> peopleId.equals(card.getPeopleId()));
    }

    private PeopleLinkRequest requireOpen(String requestId) {
        PeopleLinkRequest row = requestMapper.findById(requestId);
        if (row == null || !PeopleLinkRequest.OPEN.equals(row.getStatus())) {
            throw new BusinessException(404, "申请不存在或已处理");
        }
        return row;
    }

    private void closeRequest(PeopleLinkRequest row, String status) {
        row.setStatus(status);
        row.setResolvedAt(LocalDateTime.now());
        row.setGmtModified(row.getResolvedAt());
        requestMapper.update(row);
    }

    private void cancelTask(PeopleLinkRequest row) {
        if (!StringUtils.hasText(row.getTaskId())) {
            return;
        }
        taskMapper.close(
                row.getTaskId(),
                "CANCELLED",
                "CANCEL",
                LocalDateTime.now(),
                null,
                LocalDateTime.now());
    }

    private static String requireName(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            throw new BusinessException(400, "请填写就诊人姓名");
        }
        String name = displayName.trim();
        if (name.length() > 64) {
            throw new BusinessException(400, "姓名过长");
        }
        return name;
    }

    private static String optionalGender(String gender) {
        if (!StringUtils.hasText(gender)) {
            return null;
        }
        String g = gender.trim().toUpperCase(Locale.ROOT);
        if ("UNKNOWN".equals(g)) {
            return g;
        }
        if (!"MALE".equals(g) && !"FEMALE".equals(g)) {
            throw new BusinessException(400, "性别不正确");
        }
        return g;
    }

}
