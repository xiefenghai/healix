package com.healix.web.b.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.recipe.dto.OrgRecipeDto;
import com.healix.core.recipe.service.OrgRecipeService;
import com.healix.security.SecurityUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BOrgRecipeController {

    private final OrgRecipeService orgRecipeService;

    @GetMapping("/recipes")
    public ApiResult<PageResult<OrgRecipeDto>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(orgRecipeService.page(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                keyword,
                status,
                page,
                size));
    }

    @GetMapping("/recipes/{id}")
    public ApiResult<OrgRecipeDto> get(@PathVariable String id) {
        return ApiResult.ok(orgRecipeService.get(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), id));
    }

    @PostMapping("/recipes")
    public ApiResult<OrgRecipeDto> create(@RequestBody UpsertBody body) {
        return ApiResult.ok(orgRecipeService.create(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                toCmd(body)));
    }

    @PutMapping("/recipes/{id}")
    public ApiResult<OrgRecipeDto> update(@PathVariable String id, @RequestBody UpsertBody body) {
        return ApiResult.ok(orgRecipeService.update(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                id,
                toCmd(body)));
    }

    @DeleteMapping("/recipes/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        orgRecipeService.delete(SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), id);
        return ApiResult.ok();
    }

    @PostMapping("/patients/{peopleId}/care-plan/draft/apply-recipe")
    public ApiResult<JsonNode> applyRecipe(@PathVariable String peopleId, @RequestBody ApplyBody body) {
        return ApiResult.ok(orgRecipeService.applyToDraft(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                body == null ? null : body.recipeId(),
                body != null && Boolean.TRUE.equals(body.applyWeek())));
    }

    private static OrgRecipeService.UpsertCommand toCmd(UpsertBody body) {
        if (body == null) {
            return new OrgRecipeService.UpsertCommand(null, null, null, null, null, null, null, null);
        }
        return new OrgRecipeService.UpsertCommand(
                body.name(),
                body.description(),
                body.tags(),
                body.meal(),
                body.week(),
                body.energyKcalHint(),
                body.allergenKeywords(),
                body.status());
    }

    public record UpsertBody(
            String name,
            String description,
            List<String> tags,
            JsonNode meal,
            JsonNode week,
            Integer energyKcalHint,
            List<String> allergenKeywords,
            String status) {}

    public record ApplyBody(String recipeId, Boolean applyWeek) {}
}
