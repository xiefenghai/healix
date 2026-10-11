package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.BasicArchiveService;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.archive.service.LifestyleArchiveService;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleIdentityMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.security.SecurityUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端健康档案（只读 + 已有生活方式接口仍在 CPortalController）。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me/archive")
@RequiredArgsConstructor
public class CArchiveController {

    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleIdentityMapper peopleIdentityMapper;
    private final BasicArchiveService basicArchiveService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final LifestyleArchiveService lifestyleArchiveService;

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
        var identity = peopleIdentityMapper.findPrimaryMask(peopleId);
        out.put("identityMask", identity != null ? identity.getIdentityValueMask() : null);
        out.put("identityType", identity != null ? identity.getIdentityType() : null);
        out.put("basic", basicArchiveService.get(tenantId, peopleId));
        out.put("lifestyle", lifestyleArchiveService.get(tenantId, peopleId));
        out.put("diseases", diseaseArchiveService.list(tenantId, peopleId));
        return ApiResult.ok(out);
    }

    @GetMapping("/basic")
    public ApiResult<ArchiveViewDto> basic() {
        return ApiResult.ok(basicArchiveService.get(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
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
}
