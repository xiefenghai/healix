package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveCompletenessDto;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveCompletenessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.archive.service.BasicArchiveService;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.archive.service.LifestyleArchiveService;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleIdentityMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.security.SecurityUtils;
import com.healix.web.b.archive.SaveArchiveRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端健康档案（基础/病种可写；生活方式 PATCH 仍在 CPortalController）。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me/archive")
@RequiredArgsConstructor
public class CArchiveController {

    private static final String C_ARCHIVE_SOURCE = "C_ARCHIVE";

    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleIdentityMapper peopleIdentityMapper;
    private final BasicArchiveService basicArchiveService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final LifestyleArchiveService lifestyleArchiveService;
    private final ArchiveCompletenessService archiveCompletenessService;

    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> summary() {
        String peopleId = SecurityUtils.requirePatientId();
        String tenantId = SecurityUtils.requireTenantId();
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        Map<String, Object> out = new HashMap<>();
        out.put("peopleId", peopleId);
        out.put("displayName", profile != null ? profile.getDisplayName() : null);
        out.put("gender", profile != null ? profile.getGender() : null);
        out.put("birthday", profile != null ? profile.getBirthday() : null);
        out.put("mobileMask", maskMobile(profile != null ? profile.getMobile() : null));
        var identity = peopleIdentityMapper.findPrimaryMask(peopleId);
        out.put("identityMask", identity != null ? identity.getIdentityValueMask() : null);
        out.put("identityType", identity != null ? identity.getIdentityType() : null);
        out.put("basic", basicArchiveService.get(tenantId, peopleId));
        out.put("lifestyle", lifestyleArchiveService.get(tenantId, peopleId));
        out.put("diseases", diseaseArchiveService.list(tenantId, peopleId));
        return ApiResult.ok(out);
    }

    /**
     * 档案完整度（与健管端同一套计分：基础 FIELD 字典 + 已建病种 DISEASE_FIELD + 过敏史）。
     */
    @GetMapping("/completeness")
    public ApiResult<ArchiveCompletenessDto> completeness() {
        return ApiResult.ok(archiveCompletenessService.compute(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/basic")
    public ApiResult<ArchiveViewDto> basic() {
        return ApiResult.ok(basicArchiveService.get(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    /** 患者自助保存基础档案（乐观锁；contentJson 为全文，前端需合并后再提交）。 */
    @PutMapping("/basic")
    public ApiResult<ArchiveViewDto> saveBasic(@RequestBody @Validated SaveArchiveRequest request) {
        String peopleId = SecurityUtils.requirePatientId();
        String tenantId = SecurityUtils.requireTenantId();
        return ApiResult.ok(basicArchiveService.save(
                tenantId,
                peopleId,
                request.version(),
                JsonUtils.toJson(request.contentJson()),
                ArchiveOperatorContext.people(peopleId, C_ARCHIVE_SOURCE)));
    }

    @GetMapping("/disease")
    public ApiResult<List<DiseaseArchiveViewDto>> listDisease() {
        return ApiResult.ok(
                diseaseArchiveService.list(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/disease/{diseaseCode}")
    public ApiResult<DiseaseArchiveViewDto> getDisease(@PathVariable String diseaseCode) {
        return ApiResult.ok(diseaseArchiveService.get(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), diseaseCode));
    }

    /** 患者自助保存（或首次创建）病种档案。 */
    @PutMapping("/disease/{diseaseCode}")
    public ApiResult<DiseaseArchiveViewDto> saveDisease(
            @PathVariable String diseaseCode, @RequestBody @Validated SaveArchiveRequest request) {
        String peopleId = SecurityUtils.requirePatientId();
        String tenantId = SecurityUtils.requireTenantId();
        return ApiResult.ok(diseaseArchiveService.save(
                tenantId,
                peopleId,
                diseaseCode,
                request.version(),
                JsonUtils.toJson(request.contentJson()),
                ArchiveOperatorContext.people(peopleId, C_ARCHIVE_SOURCE)));
    }

    /** 中间四位打码，如 138****5678 */
    private static String maskMobile(String mobile) {
        if (!StringUtils.hasText(mobile)) {
            return null;
        }
        String digits = mobile.replaceAll("\\D", "");
        if (digits.startsWith("86") && digits.length() == 13) {
            digits = digits.substring(2);
        }
        if (digits.length() >= 7) {
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }
}
