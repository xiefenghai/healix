package com.healix.web.b.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.persona.dto.PersonaView;
import com.healix.security.SecurityUtils;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/persona")
@RequiredArgsConstructor
public class BPersonaController {

    private final PersonaAppService personaAppService;
    private final ArchiveAccessService archiveAccessService;

    @GetMapping
    public ApiResult<PersonaView> get(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(personaAppService.get(tenantId, orgId, peopleId));
    }

    @PostMapping("/generate")
    public ApiResult<PersonaView> generate(@PathVariable String peopleId) {
        requireWrite();
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(personaAppService.generate(
                tenantId, orgId, peopleId, SecurityUtils.requireStaffId(), SecurityUtils.requireAccountId()));
    }

    @PostMapping("/refresh")
    public ApiResult<PersonaView> refresh(@PathVariable String peopleId) {
        requireWrite();
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(personaAppService.refresh(
                tenantId, orgId, peopleId, SecurityUtils.requireStaffId(), SecurityUtils.requireAccountId()));
    }

    @PutMapping("/narrative")
    public ApiResult<PersonaView> saveNarrative(
            @PathVariable String peopleId, @RequestBody NarrativeBody body) {
        requireWrite();
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(personaAppService.saveNarrative(
                tenantId,
                orgId,
                peopleId,
                body == null ? null : body.headline(),
                body == null ? null : body.narrative(),
                body == null ? null : body.watchPoints(),
                SecurityUtils.requireStaffId(),
                SecurityUtils.requireAccountId()));
    }

    @PostMapping("/unlock")
    public ApiResult<PersonaView> unlock(
            @PathVariable String peopleId, @RequestParam(defaultValue = "false") boolean refresh) {
        requireWrite();
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(personaAppService.unlock(
                tenantId,
                orgId,
                peopleId,
                refresh,
                SecurityUtils.requireStaffId(),
                SecurityUtils.requireAccountId()));
    }

    private static void requireWrite() {
        Set<String> roles = currentRoles();
        boolean canWrite = roles.contains(StaffRoleEnum.CARE_MANAGER.name())
                || roles.contains(StaffRoleEnum.TENANT_ADMIN.name());
        if (!canWrite) {
            throw new BusinessException(403, "仅健管师或租户管理员可编辑用户画像");
        }
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return Set.of();
        }
        return ctx.getRoles();
    }

    public record NarrativeBody(String headline, String narrative, JsonNode watchPoints) {}
}
