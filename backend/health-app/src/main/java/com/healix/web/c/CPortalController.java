package com.healix.web.c;

import com.healix.agent.card.AgentConfirmCardService;
import com.healix.agent.service.HealthAgentService;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.archive.service.LifestyleArchiveService;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.govern.service.FeatureFlagService.FlagView;
import com.healix.core.govern.service.TenantConfigService;
import com.healix.core.govern.service.TenantConfigService.BrandingView;
import com.healix.core.identity.service.IdentityService;
import com.healix.core.ops.dto.TenantListItem;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.service.MembershipService;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.domain.PeopleActivationInvite;
import com.healix.core.patientcard.service.MobileOtpService;
import com.healix.core.patientcard.service.PatientActivationService;
import com.healix.core.patientcard.service.PatientCardMergeService;
import com.healix.core.patientcard.service.PatientCardService;
import com.healix.core.patientcard.service.PeopleHomeContextService;
import com.healix.core.patientcard.service.PeopleLinkService;
import com.healix.core.people.domain.PeopleAccount;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.tenant.mapper.TenantMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.VitalSourceEnum;
import com.healix.core.vitals.service.VitalService;
import com.healix.security.JwtTokenProvider;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端（患者）门户接口。
 * <p>注册登录、就诊人卡片、激活、入组、体征与健康助手等。
 */
@Validated
@RestController
@RequestMapping("/api/c/v1")
@RequiredArgsConstructor
public class CPortalController {

    private final IdentityService identityService;
    private final MembershipService membershipService;
    private final VitalService vitalService;
    private final HealthAgentService healthAgentService;
    private final AgentConfirmCardService confirmCardService;
    private final JwtTokenProvider jwtTokenProvider;
    private final LifestyleArchiveService lifestyleArchiveService;
    private final PatientCardService patientCardService;
    private final PatientActivationService patientActivationService;
    private final PatientCardMergeService patientCardMergeService;
    private final PeopleLinkService peopleLinkService;
    private final PeopleHomeContextService peopleHomeContextService;
    private final MobileOtpService mobileOtpService;
    private final TenantMapper tenantMapper;
    private final TenantConfigService tenantConfigService;
    private final FeatureFlagService featureFlagService;

    /**
     * C 端启动配置。tenantId 仅用于白标/开关，不再决定登录账号落在哪个租户。
     */
    @GetMapping("/auth/bootstrap")
    public ApiResult<BootstrapResponse> bootstrap() {
        String tenantId = resolveOptionalTenantId(null);
        BrandingView branding =
                tenantId == null ? BrandingView.platformDefault() : tenantConfigService.branding(tenantId);
        List<FlagView> flags = tenantId == null ? List.of() : featureFlagService.list(tenantId);
        return ApiResult.ok(new BootstrapResponse(tenantId, branding, flags));
    }

    /** 发送登录或添加就诊人验证码。开发环境可用 mock-code。 */
    @PostMapping("/auth/mobile-otp")
    public ApiResult<Void> sendLoginOtp(@RequestBody @Validated MobileOtpRequest request) {
        String scope = otpScope(request.tenantId(), request.activationCode());
        mobileOtpService.send(scope, "login", request.mobile());
        return ApiResult.ok(null);
    }

    /** 注册：手机号 + 验证码，平台级账号，不创建就诊人。 */
    @PostMapping("/auth/register")
    public ApiResult<TokenResponse> register(@RequestBody @Validated MobileAuthRequest request) {
        String scope = otpScope(request.tenantId(), null);
        String mobile = mobileOtpService.verify(scope, "login", request.mobile(), request.otpCode());
        PeopleAccount account = identityService.registerByMobileGlobal(mobile);
        String token = jwtTokenProvider.createPatientToken(account.getId(), null, null, null);
        return ApiResult.ok(new TokenResponse(token, account.getId(), null, null, null));
    }

    /** 登录：手机号 + 验证码（平台级账号）。 */
    @PostMapping("/auth/login")
    public ApiResult<TokenResponse> login(@RequestBody @Validated MobileAuthRequest request) {
        String scope = otpScope(request.tenantId(), null);
        String mobile = mobileOtpService.verify(scope, "login", request.mobile(), request.otpCode());
        PeopleAccount account = identityService.requireByMobileGlobal(mobile);
        AccountPatient card = patientCardService.findFirstCard(account.getId());
        String peopleId = card != null ? card.getPeopleId() : null;
        String cardId = card != null ? card.getId() : null;
        String homeTenant = resolveHomeTenant(peopleId, card);
        String token = jwtTokenProvider.createPatientToken(account.getId(), peopleId, homeTenant, cardId);
        return ApiResult.ok(new TokenResponse(token, account.getId(), peopleId, cardId, homeTenant));
    }

    @GetMapping("/account")
    public ApiResult<java.util.Map<String, String>> account() {
        var ctx = SecurityUtils.requireContext();
        PeopleAccount account = identityService.requirePeopleAccountById(ctx.getAccountId());
        return ApiResult.ok(java.util.Map.of("mobile", account.getUsername()));
    }

    /** 激活码绑定就诊人（冷启动可同时建账号）。租户以激活码为准。 */
    @PostMapping("/auth/activate")
    public ApiResult<TokenResponse> activate(@RequestBody @Validated ActivateRequest request) {
        String existingAccountId = null;
        var ctx = RequestContextHolder.get();
        if (ctx != null && ctx.getAccountId() != null) {
            existingAccountId = ctx.getAccountId();
        }
        PeopleActivationInvite invite = patientActivationService.requireUsableInvite(request.activationCode());
        String tenantId = invite.getTenantId();
        if (existingAccountId == null) {
            String mobile = mobileOtpService.verify(tenantId, "login", request.mobile(), request.otpCode());
            PeopleAccount account;
            try {
                account = identityService.registerByMobileGlobal(mobile, tenantId);
            } catch (com.healix.common.exception.BusinessException ex) {
                if (ex.getCode() != 409) {
                    throw ex;
                }
                account = identityService.requireByMobileGlobal(mobile);
            }
            existingAccountId = account.getId();
        }
        PatientActivationService.ActivateResult result = patientActivationService.activate(
                tenantId,
                request.activationCode(),
                null,
                null,
                request.displayName(),
                request.birthday(),
                request.relation(),
                existingAccountId);
        String homeTenant = resolveHomeTenant(result.people().getId(), result.card());
        String token = jwtTokenProvider.createPatientToken(
                result.account().getId(),
                result.people().getId(),
                homeTenant,
                result.card().getId());
        return ApiResult.ok(new TokenResponse(
                token,
                result.account().getId(),
                result.people().getId(),
                result.card().getId(),
                homeTenant));
    }

    @GetMapping("/patient-cards")
    public ApiResult<List<AccountPatient>> listPatientCards() {
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(patientCardService.listByAccount(accountId));
    }

    @PostMapping("/patient-cards")
    public ApiResult<CardSubmitResponse> createPatientCard(@RequestBody @Validated CreatePatientCardRequest request) {
        var ctx = SecurityUtils.requireContext();
        // 新建 people 时过渡写入存储租户；自动绑档靠手机号+姓名+生日，不依赖租户
        String tenantId = ctx.getTenantId();
        if (!StringUtils.hasText(tenantId)) {
            AccountPatient first = patientCardService.findFirstCard(ctx.getAccountId());
            tenantId = first != null ? first.getTenantId() : resolveOptionalTenantId(null);
        }
        PeopleLinkService.CardSubmitResult result = peopleLinkService.submitCard(
                tenantId,
                ctx.getAccountId(),
                request.displayName(),
                request.gender(),
                request.birthday(),
                request.relation(),
                request.mobile(),
                request.otpCode(),
                request.identityType(),
                request.identityValue());
        String token = null;
        String peopleId = ctx.getPatientId();
        String cardId = ctx.getActivePatientCardId();
        if (result.card() != null) {
            peopleId = result.card().getPeopleId();
            cardId = result.card().getId();
            String homeTenant = resolveHomeTenant(peopleId, result.card());
            token = jwtTokenProvider.createPatientToken(ctx.getAccountId(), peopleId, homeTenant, cardId);
        }
        return ApiResult.ok(new CardSubmitResponse(result.outcome(), result.message(), token, peopleId, cardId));
    }

    @GetMapping("/patient-link-requests")
    public ApiResult<List<PeopleLinkService.LinkRequestView>> listLinkRequests() {
        return ApiResult.ok(peopleLinkService.listRequests(SecurityUtils.requireContext().getAccountId()));
    }

    @PostMapping("/patient-link-requests/{id}/withdraw")
    public ApiResult<Void> withdrawLinkRequest(@PathVariable String id) {
        peopleLinkService.withdraw(SecurityUtils.requireContext().getAccountId(), id);
        return ApiResult.ok(null);
    }

    /** 给就诊人本人号发验证码（添加就诊人强制 OTP，登录号也不豁免）。 */
    @PostMapping("/mobile-otp")
    public ApiResult<Void> sendPatientOtp(@RequestBody @Validated PatientOtpRequest request) {
        var ctx = SecurityUtils.requireContext();
        String scope = StringUtils.hasText(ctx.getTenantId()) ? ctx.getTenantId() : "platform";
        mobileOtpService.send(scope, ctx.getAccountId(), request.mobile());
        return ApiResult.ok(null);
    }

    /** 激活后检查：账号里是否有自己添加过的同一人记录，可并入机构档案。 */
    @GetMapping("/patient-cards/{cardId}/merge-candidate")
    public ApiResult<PatientCardMergeService.MergeCandidate> mergeCandidate(@PathVariable String cardId) {
        return ApiResult.ok(patientCardMergeService.findCandidate(
                SecurityUtils.requireContext().getAccountId(), cardId));
    }

    @PostMapping("/patient-cards/{cardId}/merge-from/{sourceCardId}")
    public ApiResult<TokenResponse> mergeFrom(@PathVariable String cardId, @PathVariable String sourceCardId) {
        var ctx = SecurityUtils.requireContext();
        patientCardMergeService.merge(ctx.getAccountId(), cardId, sourceCardId);
        AccountPatient card = patientCardService.requireOwnedCard(ctx.getAccountId(), cardId);
        String homeTenant = resolveHomeTenant(card.getPeopleId(), card);
        String token = jwtTokenProvider.createPatientToken(
                ctx.getAccountId(), card.getPeopleId(), homeTenant, card.getId());
        return ApiResult.ok(new TokenResponse(
                token, ctx.getAccountId(), card.getPeopleId(), card.getId(), homeTenant));
    }

    @PostMapping("/patient-cards/{cardId}/select")
    public ApiResult<TokenResponse> selectPatientCard(@PathVariable String cardId) {
        var ctx = SecurityUtils.requireContext();
        AccountPatient card = patientCardService.requireOwnedCard(ctx.getAccountId(), cardId);
        String homeTenant = resolveHomeTenant(card.getPeopleId(), card);
        String token = jwtTokenProvider.createPatientToken(
                ctx.getAccountId(), card.getPeopleId(), homeTenant, card.getId());
        return ApiResult.ok(new TokenResponse(
                token, ctx.getAccountId(), card.getPeopleId(), card.getId(), homeTenant));
    }

    @DeleteMapping("/patient-cards/{cardId}")
    public ApiResult<TokenResponse> deletePatientCard(@PathVariable String cardId) {
        var ctx = SecurityUtils.requireContext();
        patientCardService.unlink(ctx.getAccountId(), cardId);
        AccountPatient next = patientCardService.findFirstCard(ctx.getAccountId());
        String peopleId = next != null ? next.getPeopleId() : null;
        String nextCardId = next != null ? next.getId() : null;
        String homeTenant = resolveHomeTenant(peopleId, next);
        String token = jwtTokenProvider.createPatientToken(ctx.getAccountId(), peopleId, homeTenant, nextCardId);
        return ApiResult.ok(new TokenResponse(token, ctx.getAccountId(), peopleId, nextCardId, homeTenant));
    }

    /** 编辑家庭成员资料（姓名/关系；自用态可改性别生日；证件仅可补填）。 */
    @PutMapping("/patient-cards/{cardId}")
    public ApiResult<AccountPatient> updatePatientCard(
            @PathVariable String cardId, @RequestBody @Validated UpdatePatientCardRequest request) {
        var ctx = SecurityUtils.requireContext();
        return ApiResult.ok(patientCardService.updateOwnedCard(
                ctx.getAccountId(),
                cardId,
                request.displayName(),
                request.relation(),
                request.gender(),
                request.birthday(),
                request.identityType(),
                request.identityValue()));
    }

    @GetMapping("/membership/status")
    public ApiResult<MembershipStatusResponse> membershipStatus() {
        String peopleId = SecurityUtils.requirePatientId();
        boolean inCare = peopleHomeContextService.hasActiveMembership(peopleId);
        String homeTenant = peopleHomeContextService.resolveHomeTenantId(peopleId);
        return ApiResult.ok(new MembershipStatusResponse(inCare, homeTenant));
    }

    @PutMapping("/profile")
    public ApiResult<PeopleProfile> updateProfile(@RequestBody @Validated UpdateProfileRequest request) {
        String peopleId = SecurityUtils.requirePatientId();
        return ApiResult.ok(identityService.updatePeopleProfile(
                peopleId, request.displayName(), request.gender(), request.birthday(), request.allergensJson()));
    }

    @GetMapping("/profile")
    public ApiResult<PeopleProfile> profile() {
        return ApiResult.ok(identityService.requirePeople(SecurityUtils.requirePatientId()));
    }

    @PostMapping("/membership/join")
    public ApiResult<PatientOrgMembership> join(@RequestBody @Validated JoinRequest request) {
        String peopleId = SecurityUtils.requirePatientId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(membershipService.joinByInvite(peopleId, accountId, request.inviteCode()));
    }

    @PostMapping("/vitals")
    public ApiResult<VitalRecord> recordVital(@RequestBody @Validated VitalRequest request) {
        String peopleId = SecurityUtils.requirePatientId();
        return ApiResult.ok(vitalService.record(
                peopleId,
                request.metricType(),
                request.value(),
                request.unit(),
                request.recordedAt(),
                VitalSourceEnum.SELF.name(),
                request.mealContext(),
                request.bpContext()));
    }

    @GetMapping("/vitals")
    public ApiResult<List<VitalRecord>> listVitals(
            @RequestParam String metricType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        String peopleId = SecurityUtils.requirePatientId();
        String tenantId = SecurityUtils.requireContext().getTenantId();
        return ApiResult.ok(vitalService.list(tenantId, peopleId, metricType, from, to));
    }

    @PostMapping("/agent/chat")
    public ApiResult<?> chat(@RequestBody @Validated ChatRequest request) {
        return ApiResult.ok(healthAgentService.chat(SecurityUtils.requirePatientId(), request.message()));
    }

    @PostMapping("/agent/session/new")
    public ApiResult<Void> newAgentSession() {
        healthAgentService.startNew(SecurityUtils.requirePatientId());
        return ApiResult.ok();
    }

    @PatchMapping("/agent/cards/{id}")
    public ApiResult<AgentConfirmCardService.AgentConfirmCardView> updateCard(
            @PathVariable String id, @RequestBody CardContextRequest request) {
        return ApiResult.ok(confirmCardService.updateContext(
                SecurityUtils.requirePatientId(), id, request.bpContext(), request.mealContext()));
    }

    @PostMapping("/agent/cards/{id}/confirm")
    public ApiResult<CardActionResponse> confirmCard(@PathVariable String id) {
        String reply = confirmCardService.confirm(SecurityUtils.requirePatientId(), id);
        return ApiResult.ok(new CardActionResponse(reply));
    }

    @PostMapping("/agent/cards/{id}/cancel")
    public ApiResult<CardActionResponse> cancelCard(@PathVariable String id) {
        confirmCardService.cancel(SecurityUtils.requirePatientId(), id);
        return ApiResult.ok(new CardActionResponse("已取消，没有记入。"));
    }

    @GetMapping("/me/archive/lifestyle")
    public ApiResult<ArchiveViewDto> getLifestyleArchive() {
        String peopleId = SecurityUtils.requirePatientId();
        String tenantId = SecurityUtils.requireTenantId();
        return ApiResult.ok(lifestyleArchiveService.get(tenantId, peopleId));
    }

    @PatchMapping("/me/archive/lifestyle")
    public ApiResult<ArchiveViewDto> patchLifestyleArchive(@RequestBody @Validated LifestylePatchRequest request) {
        String peopleId = SecurityUtils.requirePatientId();
        String tenantId = SecurityUtils.requireTenantId();
        return ApiResult.ok(lifestyleArchiveService.patch(
                tenantId, peopleId, request.version(), JsonUtils.toJson(request.contentJson())));
    }

    private String resolveHomeTenant(String peopleId, AccountPatient card) {
        String fromPeople = peopleHomeContextService.resolveHomeTenantId(peopleId);
        if (StringUtils.hasText(fromPeople)) {
            return fromPeople;
        }
        if (card != null && StringUtils.hasText(card.getTenantId())) {
            return card.getTenantId();
        }
        return null;
    }

    /** OTP 作用域：优先激活码租户，其次请求/会话租户，否则 platform（与账号解耦）。 */
    private String otpScope(String tenantId, String activationCode) {
        if (StringUtils.hasText(activationCode)) {
            return patientActivationService.requireUsableInvite(activationCode).getTenantId();
        }
        if (StringUtils.hasText(tenantId)) {
            return tenantId.trim();
        }
        return "platform";
    }

    private String resolveOptionalTenantId(String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            return tenantId.trim();
        }
        List<TenantListItem> list = tenantMapper.list(null, 0, 1);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0).getTenantId();
    }

    /**
     * @param branding 白标配置；未开通白标时各字段为空
     * @param flags 功能开关，前端据此隐藏未开通入口
     */
    public record BootstrapResponse(String tenantId, BrandingView branding, List<FlagView> flags) {}

    /** activationCode 可选：激活页带码时按码所属租户发验证码。 */
    public record MobileOtpRequest(String tenantId, String activationCode, @NotBlank String mobile) {}

    public record MobileAuthRequest(String tenantId, @NotBlank String mobile, @NotBlank String otpCode) {}

    public record RegisterRequest(String tenantId, @NotBlank String username, @NotBlank String password) {}

    public record LoginRequest(String tenantId, @NotBlank String username, @NotBlank String password) {}

    public record ActivateRequest(
            String tenantId,
            @NotBlank String activationCode,
            String mobile,
            String otpCode,
            String displayName,
            LocalDate birthday,
            String relation) {}

    /**
     * 自动绑档：手机号 + 姓名 + 出生日期；证件/性别选填。
     * <p>关系为本人且手机号等于登录账号时 otpCode 可空（登录态已验证该号）；其余情况 otpCode 必填。
     */
    public record CreatePatientCardRequest(
            @NotBlank String displayName,
            String gender,
            LocalDate birthday,
            String identityType,
            String identityValue,
            String relation,
            @NotBlank String mobile,
            String otpCode) {}

    public record PatientOtpRequest(@NotBlank String mobile) {}

    public record CardSubmitResponse(
            String outcome, String message, String accessToken, String patientId, String patientCardId) {}

    public record UpdateProfileRequest(
            String displayName, String gender, LocalDate birthday, String allergensJson) {}

    public record UpdatePatientCardRequest(
            @NotBlank String displayName,
            @NotBlank String relation,
            String gender,
            LocalDate birthday,
            String identityType,
            String identityValue) {}

    public record JoinRequest(@NotBlank String inviteCode) {}

    public record VitalRequest(
            @NotBlank String metricType,
            @NotNull BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String mealContext,
            String bpContext) {}

    public record ChatRequest(@NotBlank String message) {}

    public record CardContextRequest(String bpContext, String mealContext) {}

    public record CardActionResponse(String reply) {}

    public record LifestylePatchRequest(@NotNull Integer version, @NotNull java.util.Map<String, Object> contentJson) {}

    public record TokenResponse(
            String accessToken, String accountId, String peopleId, String patientCardId, String tenantId) {}

    public record MembershipStatusResponse(boolean inCare, String homeTenantId) {}
}
