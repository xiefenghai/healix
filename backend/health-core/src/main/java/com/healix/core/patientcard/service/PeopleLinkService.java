package com.healix.core.patientcard.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.domain.PeopleLinkRequest;
import com.healix.core.patientcard.mapper.PeopleLinkRequestMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.enums.MobileOwnerEnum;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.people.service.PeopleMobileStateService;
import com.healix.core.people.support.MobileNumbers;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 就诊人认档：OTP 证明手机号在手上；自动绑档须「本人手机号 + 姓名 + 出生日期」一致。
 * <p>同号同名且生日一致 → 复用 people；同号同名但生日对不上 → 交健管核对（不静默绑档）；
 * 同号异名 → 视为不同人可新建（家庭成员）。
 */
@Service
@RequiredArgsConstructor
public class PeopleLinkService {

    public static final String PENDING_MESSAGE = "这个号码已有档案，姓名或出生日期不一致，已交给健康管理团队核对";

    /** 历史自助建档占位生日，不参与自动认人。 */
    private static final LocalDate PLACEHOLDER_BIRTHDAY = LocalDate.of(1970, 1, 1);

    public record CardSubmitResult(String outcome, AccountPatient card, String message, String requestId) {}

    public record LinkRequestView(String id, String status, String displayName, String mobileTail, String message) {}

    private final MobileOtpService mobileOtpService;
    private final PatientCardService patientCardService;
    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleLinkRequestMapper requestMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final OrganizationMapper organizationMapper;
    private final PeopleMobileStateService mobileStateService;
    private final WorkspaceTaskGenerator taskGenerator;
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
        LocalDate birthdayNorm = requireBirthday(birthday);
        String genderNorm = optionalGender(gender);
        if (genderNorm == null) {
            genderNorm = "UNKNOWN";
        }

        // OTP 只证明「号在手上」，不能单凭姓名偷绑他人档案
        String otpScope = StringUtils.hasText(tenantId) ? tenantId : "platform";
        mobileOtpService.verify(otpScope, accountId, self, otpCode);

        if (patientCardService.findSamePersonCardByMobileName(accountId, self, name) != null) {
            throw new BusinessException(409, "「" + name + "」已在您的就诊人列表中");
        }

        PeopleProfile target = peopleProfileMapper.findBySelfMobileAndName(self, name);
        if (target != null) {
            if (alreadyLinked(accountId, target.getId())) {
                throw new BusinessException(409, "「" + target.getDisplayName() + "」已在您的就诊人列表中");
            }
            // 自动绑档：手机号 + 姓名 + 真实生日 三者一致；缺一不可
            if (isRealBirthday(target.getBirthday()) && birthdayNorm.equals(target.getBirthday())) {
                rememberOptionalDemographics(target, genderNorm, birthdayNorm);
                ensureSelfMobile(target, self);
                String cardTenant = StringUtils.hasText(target.getTenantId()) ? target.getTenantId() : tenantId;
                AccountPatient card =
                        patientCardService.linkExistingPeople(cardTenant, accountId, target, relation, null);
                return new CardSubmitResult("LINKED", card, null, null);
            }
            // 同号同名但对不上生日（或档上无可靠生日）→ 不静默授权，交健管核对
            String requestTenant = StringUtils.hasText(target.getTenantId())
                    ? target.getTenantId()
                    : (StringUtils.hasText(tenantId) ? tenantId : null);
            PeopleLinkRequest pending = openLinkRequest(
                    requestTenant, accountId, self, name, genderNorm, birthdayNorm, relation, null, target);
            return new CardSubmitResult("PENDING", null, PENDING_MESSAGE, pending.getId());
        }

        // 同号异名或该号尚无档案 → 新建独立 people（家庭成员）
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

    private PeopleLinkRequest openLinkRequest(
            String tenantId,
            String accountId,
            String mobile,
            String name,
            String gender,
            LocalDate birthday,
            String relation,
            String sourcePeopleId,
            PeopleProfile target) {
        String scopeTenant = StringUtils.hasText(tenantId) ? tenantId : target.getTenantId();
        if (!StringUtils.hasText(scopeTenant)) {
            throw new BusinessException(400, "暂时无法提交核对，请联系健康管理团队");
        }
        if (requestMapper.findOpenLink(scopeTenant, accountId, mobile) != null) {
            throw new BusinessException(409, "该号码已有未处理的关联申请");
        }
        int used = patientCardService.listByAccount(accountId).size() + requestMapper.countOpenLink(accountId);
        if (used >= PatientCardService.MAX_CARDS_PER_ACCOUNT) {
            throw new BusinessException(409, "就诊人数量已达上限");
        }
        String orgId = resolveOrg(scopeTenant, target.getId());
        PeopleLinkRequest row = new PeopleLinkRequest();
        row.setTenantId(scopeTenant);
        row.setAccountId(accountId);
        row.setKind(PeopleLinkRequest.KIND_LINK);
        row.setStatus(PeopleLinkRequest.OPEN);
        row.setMobile(mobile);
        row.setSourcePeopleId(sourcePeopleId);
        row.setTargetPeopleId(target.getId());
        row.setOrgId(orgId);
        row.setDisplayName(name);
        row.setGender(gender);
        row.setBirthday(birthday);
        row.setRelation(relation);
        row.setExpireAt(LocalDateTime.now().plusDays(7));
        EntityMeta.onCreate(row);
        requestMapper.insert(row);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", row.getId());
        payload.put("applicantName", name);
        payload.put("applicantGender", genderLabel(gender));
        payload.put("applicantBirthday", birthday.toString());
        payload.put("archiveName", target.getDisplayName());
        payload.put("archiveGender", genderLabel(target.getGender()));
        payload.put("archiveBirthday", target.getBirthday() == null ? "" : target.getBirthday().toString());
        payload.put("mobileTail", MobileNumbers.tail(mobile));
        String taskId = taskGenerator.openPeopleRequestTask(
                scopeTenant,
                orgId,
                target.getId(),
                WorkspaceTaskType.PROFILE_LINK,
                row.getId(),
                "请核对是否同一人：尾号" + MobileNumbers.tail(mobile),
                payload);
        row.setTaskId(taskId);
        row.setGmtModified(LocalDateTime.now());
        requestMapper.update(row);
        return row;
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
        if (birthday != null && isRealBirthday(birthday) && !isRealBirthday(profile.getBirthday())) {
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

    private String resolveOrg(String tenantId, String peopleId) {
        List<PatientOrgMembership> memberships = membershipMapper.listActiveByPeople(peopleId);
        if (!memberships.isEmpty()) {
            return memberships.get(0).getOrgId();
        }
        List<Organization> orgs = organizationMapper.listByTenant(tenantId);
        if (orgs == null || orgs.isEmpty()) {
            throw new BusinessException(400, "暂时无法提交，请联系健康管理团队");
        }
        return orgs.get(0).getId();
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

    private static LocalDate requireBirthday(LocalDate birthday) {
        if (birthday == null) {
            throw new BusinessException(400, "请选择出生日期");
        }
        if (birthday.isAfter(LocalDate.now())) {
            throw new BusinessException(400, "出生日期不能晚于今天");
        }
        if (!isRealBirthday(birthday)) {
            throw new BusinessException(400, "请选择有效的出生日期");
        }
        return birthday;
    }

    private static boolean isRealBirthday(LocalDate birthday) {
        return birthday != null && !PLACEHOLDER_BIRTHDAY.equals(birthday);
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

    private static String genderLabel(String gender) {
        if ("MALE".equals(gender)) {
            return "男";
        }
        if ("FEMALE".equals(gender)) {
            return "女";
        }
        return StringUtils.hasText(gender) ? gender : "未填写";
    }
}
