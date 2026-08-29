package com.healix.web.c;

import com.healix.agent.service.HealthAgentService;
import com.healix.common.result.ApiResult;
import com.healix.core.identity.service.IdentityService;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.service.MembershipService;
import com.healix.core.people.domain.PeopleAccount;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.archive.service.LifestyleArchiveService;
import com.healix.common.util.JsonUtils;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/auth/register")
    public ApiResult<TokenResponse> register(@RequestBody @Validated RegisterRequest request) {
        PeopleProfile profile = identityService.registerPeople(
                request.tenantId(), request.username(), request.password(), request.displayName());
        String token = jwtTokenProvider.createPatientToken(
                profile.getAccountId(), profile.getId(), profile.getTenantId());
        return ApiResult.ok(new TokenResponse(token, profile.getAccountId(), profile.getId(), profile.getTenantId()));
    }

    @PostMapping("/auth/login")
    public ApiResult<TokenResponse> login(@RequestBody @Validated LoginRequest request) {
        PeopleAccount account = identityService.requirePeopleAccount(request.tenantId(), request.username());
        if (!identityService.matches(request.password(), account.getPasswordHash())) {
            throw new com.healix.common.exception.BusinessException(401, "用户名或密码错误");
        }
        PeopleProfile profile = identityService.requirePeopleByAccount(account.getId());
        String token = jwtTokenProvider.createPatientToken(
                account.getId(), profile.getId(), profile.getTenantId());
        return ApiResult.ok(new TokenResponse(token, account.getId(), profile.getId(), profile.getTenantId()));
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
                VitalSourceEnum.SELF.name()));
    }

    @GetMapping("/vitals")
    public ApiResult<List<VitalRecord>> listVitals(
            @RequestParam String metricType, @RequestParam LocalDateTime from, @RequestParam LocalDateTime to) {
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

    public record RegisterRequest(
            @NotNull String tenantId, @NotBlank String username, @NotBlank String password, String displayName) {
    }

    public record LoginRequest(@NotNull String tenantId, @NotBlank String username, @NotBlank String password) {
    }

    public record UpdateProfileRequest(
            String displayName, String gender, LocalDate birthday, String allergensJson) {
    }

    public record JoinRequest(@NotBlank String inviteCode) {
    }

    public record VitalRequest(
            @NotBlank String metricType, @NotNull BigDecimal value, String unit, LocalDateTime recordedAt) {
    }

    public record ChatRequest(@NotBlank String message) {
    }

    public record LifestylePatchRequest(@NotNull Integer version, @NotNull java.util.Map<String, Object> contentJson) {
    }

    public record TokenResponse(String accessToken, String accountId, String peopleId, String tenantId) {
    }
}
