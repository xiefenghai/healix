package com.healix.web.c;

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
import com.healix.core.patientcard.service.PatientActivationService;
import com.healix.core.patientcard.service.PatientCardService;
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
    private final JwtTokenProvider jwtTokenProvider;
    private final LifestyleArchiveService lifestyleArchiveService;
    private final PatientCardService patientCardService;
    private final PatientActivationService patientActivationService;
    private final TenantMapper tenantMapper;
    private final TenantConfigService tenantConfigService;
    private final FeatureFlagService featureFlagService;

    /** 开发期默认租户（取库中第一个），供 C 端写死/配置前使用。 */
    @GetMapping("/auth/bootstrap")
    public ApiResult<BootstrapResponse> bootstrap() {
        String tenantId = resolveTenantId(null);
        return ApiResult.ok(new BootstrapResponse(
                tenantId,
                tenantConfigService.branding(tenantId),
                featureFlagService.list(tenantId)));
    }

    /** 注册：只建账号，不创建就诊人。 */
    @PostMapping("/auth/register")
    public ApiResult<TokenResponse> register(@RequestBody @Validated RegisterRequest request) {
        String tenantId = resolveTenantId(request.tenantId());
        PeopleAccount account =
                identityService.registerAccount(tenantId, request.username(), request.password());
        String token = jwtTokenProvider.createPatientToken(account.getId(), null, tenantId, null);
        return ApiResult.ok(new TokenResponse(token, account.getId(), null, null, tenantId));
    }

    /** 登录；若已有就诊人则默认选第一张卡片。 */
    @PostMapping("/auth/login")
    public ApiResult<TokenResponse> login(@RequestBody @Validated LoginRequest request) {
        String tenantId = resolveTenantId(request.tenantId());
        PeopleAccount account = identityService.requirePeopleAccount(tenantId, request.username());
        if (!identityService.matches(request.password(), account.getPasswordHash())) {
            throw new com.healix.common.exception.BusinessException(401, "用户名或密码错误");
        }
        AccountPatient card = patientCardService.findFirstCard(account.getId());
        String peopleId = card != null ? card.getPeopleId() : null;
        String cardId = card != null ? card.getId() : null;
        String token = jwtTokenProvider.createPatientToken(account.getId(), peopleId, tenantId, cardId);
        return ApiResult.ok(new TokenResponse(token, account.getId(), peopleId, cardId, tenantId));
    }

    /** 激活码绑定就诊人（冷启动可同时建账号）。 */
    @PostMapping("/auth/activate")
    public ApiResult<TokenResponse> activate(@RequestBody @Validated ActivateRequest request) {
        String existingAccountId = null;
        var ctx = RequestContextHolder.get();
        if (ctx != null && ctx.getAccountId() != null) {
            existingAccountId = ctx.getAccountId();
        }
        PatientActivationService.ActivateResult result = patientActivationService.activate(
                request.tenantId(),
                request.activationCode(),
                request.username(),
                request.password(),
                request.displayName(),
                request.relation(),
                existingAccountId);
        String token = jwtTokenProvider.createPatientToken(
                result.account().getId(),
                result.people().getId(),
                result.account().getTenantId(),
                result.card().getId());
        return ApiResult.ok(new TokenResponse(
                token,
                result.account().getId(),
                result.people().getId(),
                result.card().getId(),
                result.account().getTenantId()));
    }

    @GetMapping("/patient-cards")
    public ApiResult<List<AccountPatient>> listPatientCards() {
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(patientCardService.listByAccount(accountId));
    }

    @PostMapping("/patient-cards")
    public ApiResult<TokenResponse> createPatientCard(@RequestBody @Validated CreatePatientCardRequest request) {
        var ctx = SecurityUtils.requireContext();
        String tenantId = ctx.getTenantId() != null ? ctx.getTenantId() : resolveTenantId(null);
        AccountPatient card = patientCardService.createSelfServe(
                tenantId,
                ctx.getAccountId(),
                request.displayName(),
                request.identityType(),
                request.identityValue(),
                request.relation(),
                request.mobile());
        String token = jwtTokenProvider.createPatientToken(
                ctx.getAccountId(), card.getPeopleId(), tenantId, card.getId());
        return ApiResult.ok(new TokenResponse(
                token, ctx.getAccountId(), card.getPeopleId(), card.getId(), tenantId));
    }

    @PostMapping("/patient-cards/{cardId}/select")
    public ApiResult<TokenResponse> selectPatientCard(@PathVariable String cardId) {
        var ctx = SecurityUtils.requireContext();
        AccountPatient card = patientCardService.requireOwnedCard(ctx.getAccountId(), cardId);
        String tenantId = card.getTenantId();
        String token = jwtTokenProvider.createPatientToken(
                ctx.getAccountId(), card.getPeopleId(), tenantId, card.getId());
        return ApiResult.ok(new TokenResponse(
                token, ctx.getAccountId(), card.getPeopleId(), card.getId(), tenantId));
    }

    @DeleteMapping("/patient-cards/{cardId}")
    public ApiResult<TokenResponse> deletePatientCard(@PathVariable String cardId) {
        var ctx = SecurityUtils.requireContext();
        patientCardService.unlink(ctx.getAccountId(), cardId);
        AccountPatient next = patientCardService.findFirstCard(ctx.getAccountId());
        String peopleId = next != null ? next.getPeopleId() : null;
        String nextCardId = next != null ? next.getId() : null;
        String tenantId = ctx.getTenantId() != null ? ctx.getTenantId() : resolveTenantId(null);
        String token = jwtTokenProvider.createPatientToken(ctx.getAccountId(), peopleId, tenantId, nextCardId);
        return ApiResult.ok(new TokenResponse(token, ctx.getAccountId(), peopleId, nextCardId, tenantId));
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

    private String resolveTenantId(String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            return tenantId.trim();
        }
        List<TenantListItem> list = tenantMapper.list(null, 0, 1);
        if (list == null || list.isEmpty()) {
            throw new com.healix.common.exception.BusinessException(400, "请指定租户");
        }
        return list.get(0).getTenantId();
    }

    /**
     * @param branding 白标配置；未开通白标时各字段为空
     * @param flags 功能开关，前端据此隐藏未开通入口
     */
    public record BootstrapResponse(String tenantId, BrandingView branding, List<FlagView> flags) {}

    public record RegisterRequest(String tenantId, @NotBlank String username, @NotBlank String password) {}

    public record LoginRequest(String tenantId, @NotBlank String username, @NotBlank String password) {}

    public record ActivateRequest(
            String tenantId,
            @NotBlank String activationCode,
            String username,
            String password,
            String displayName,
            String relation) {}

    public record CreatePatientCardRequest(
            @NotBlank String displayName,
            @NotBlank String identityType,
            @NotBlank String identityValue,
            String relation,
            String mobile) {}

    public record UpdateProfileRequest(
            String displayName, String gender, LocalDate birthday, String allergensJson) {}

    public record JoinRequest(@NotBlank String inviteCode) {}

    public record VitalRequest(
            @NotBlank String metricType,
            @NotNull BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String mealContext,
            String bpContext) {}

    public record ChatRequest(@NotBlank String message) {}

    public record LifestylePatchRequest(@NotNull Integer version, @NotNull java.util.Map<String, Object> contentJson) {}

    public record TokenResponse(
            String accessToken, String accountId, String peopleId, String patientCardId, String tenantId) {}
}
