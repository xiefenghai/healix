package com.healix.core.recipe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.PageResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.domain.CarePlanDraft;
import com.healix.core.careplan.mapper.CarePlanDraftMapper;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.support.CarePlanAllergenGuard;
import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanSchemaVersions;
import com.healix.core.recipe.domain.OrgRecipe;
import com.healix.core.recipe.dto.OrgRecipeDto;
import com.healix.core.recipe.mapper.OrgRecipeMapper;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OrgRecipeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrgRecipeMapper recipeMapper;
    private final CarePlanMapper carePlanMapper;
    private final CarePlanDraftMapper draftMapper;
    private final CarePlanContextService contextService;

    public PageResult<OrgRecipeDto> page(
            String tenantId, String orgId, String keyword, String status, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * s;
        long total = recipeMapper.countByOrg(tenantId, orgId, keyword, status);
        List<OrgRecipeDto> list = recipeMapper.listByOrg(tenantId, orgId, keyword, status, offset, s).stream()
                .map(this::toDto)
                .toList();
        return new PageResult<>(total, list);
    }

    public OrgRecipeDto get(String tenantId, String orgId, String id) {
        OrgRecipe row = requireOwned(tenantId, orgId, id);
        return toDto(row);
    }

    @Transactional
    public OrgRecipeDto create(String tenantId, String orgId, String staffId, UpsertCommand cmd) {
        validate(cmd);
        OrgRecipe row = new OrgRecipe();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        apply(row, cmd);
        row.setStatus(StringUtils.hasText(cmd.status()) ? cmd.status().trim() : "ENABLED");
        row.setCreatedByStaffId(staffId);
        row.setUpdatedByStaffId(staffId);
        EntityMeta.onCreate(row);
        recipeMapper.insert(row);
        return toDto(row);
    }

    @Transactional
    public OrgRecipeDto update(String tenantId, String orgId, String staffId, String id, UpsertCommand cmd) {
        validate(cmd);
        OrgRecipe row = requireOwned(tenantId, orgId, id);
        apply(row, cmd);
        if (StringUtils.hasText(cmd.status())) {
            row.setStatus(cmd.status().trim());
        }
        row.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(row);
        recipeMapper.update(row);
        return toDto(row);
    }

    @Transactional
    public void delete(String tenantId, String orgId, String id) {
        requireOwned(tenantId, orgId, id);
        recipeMapper.softDelete(id);
    }

    /**
     * 将食谱应用到当前草稿的 sampleDay / sampleWeek（须已有草稿）。
     *
     * @return 更新后的 diet JSON
     */
    @Transactional
    public JsonNode applyToDraft(
            String tenantId, String orgId, String peopleId, String staffId, String recipeId, boolean applyWeek) {
        OrgRecipe recipe = requireOwned(tenantId, orgId, recipeId);
        if (!"ENABLED".equalsIgnoreCase(recipe.getStatus())) {
            throw new BusinessException(400, "食谱未启用");
        }
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            throw new BusinessException("请先创建管理方案");
        }
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft == null) {
            throw new BusinessException("无草稿可应用，请先生成或新建空白草稿");
        }
        ObjectNode diet;
        try {
            JsonNode raw = JsonUtils.readTree(draft.getDietJson() == null ? "{}" : draft.getDietJson());
            diet = raw != null && raw.isObject() ? (ObjectNode) raw.deepCopy() : JsonUtils.emptyObject();
        } catch (Exception e) {
            diet = JsonUtils.emptyObject();
        }
        if (StringUtils.hasText(recipe.getMealJson())) {
            diet.set("sampleDay", JsonUtils.readTree(recipe.getMealJson()));
        }
        if (applyWeek && StringUtils.hasText(recipe.getWeekJson())) {
            diet.set("sampleWeek", JsonUtils.readTree(recipe.getWeekJson()));
        }
        CarePlanContext ctx = contextService.load(tenantId, peopleId);
        CarePlanAllergenGuard.enforce(diet, ctx.foodAllergens());
        draft.setDietJson(JsonUtils.toJson(diet));
        draft.setSchemaVersion(CarePlanSchemaVersions.CURRENT);
        draft.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(draft);
        draftMapper.update(draft);
        return diet;
    }

    private OrgRecipe requireOwned(String tenantId, String orgId, String id) {
        OrgRecipe row = recipeMapper.findById(id);
        if (row == null || !tenantId.equals(row.getTenantId()) || !orgId.equals(row.getOrgId())) {
            throw new BusinessException("食谱不存在");
        }
        return row;
    }

    private static void validate(UpsertCommand cmd) {
        if (cmd == null || !StringUtils.hasText(cmd.name())) {
            throw new BusinessException(400, "请填写食谱名称");
        }
        if (cmd.meal() == null || cmd.meal().isNull() || !cmd.meal().isObject()) {
            throw new BusinessException(400, "请填写示例日 meal");
        }
    }

    private static void apply(OrgRecipe row, UpsertCommand cmd) {
        row.setName(cmd.name().trim());
        row.setDescription(StringUtils.hasText(cmd.description()) ? cmd.description().trim() : null);
        row.setTagsJson(cmd.tags() == null || cmd.tags().isEmpty() ? null : JsonUtils.toJson(cmd.tags()));
        row.setMealJson(JsonUtils.toJson(cmd.meal()));
        row.setWeekJson(cmd.week() == null || cmd.week().isNull() ? null : JsonUtils.toJson(cmd.week()));
        row.setEnergyKcalHint(cmd.energyKcalHint());
        row.setAllergenKeywordsJson(
                cmd.allergenKeywords() == null || cmd.allergenKeywords().isEmpty()
                        ? null
                        : JsonUtils.toJson(cmd.allergenKeywords()));
    }

    private OrgRecipeDto toDto(OrgRecipe row) {
        OrgRecipeDto dto = new OrgRecipeDto();
        dto.setId(row.getId());
        dto.setOrgId(row.getOrgId());
        dto.setName(row.getName());
        dto.setDescription(row.getDescription());
        dto.setTags(stringList(row.getTagsJson()));
        dto.setMeal(parseJson(row.getMealJson()));
        dto.setWeek(parseJson(row.getWeekJson()));
        dto.setEnergyKcalHint(row.getEnergyKcalHint());
        dto.setAllergenKeywords(stringList(row.getAllergenKeywordsJson()));
        dto.setStatus(row.getStatus());
        dto.setCreatedByStaffId(row.getCreatedByStaffId());
        dto.setUpdatedByStaffId(row.getUpdatedByStaffId());
        dto.setGmtModified(row.getGmtModified() == null ? null : row.getGmtModified().format(TS));
        return dto;
    }

    private static JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return JsonUtils.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> stringList(String json) {
        List<String> out = new ArrayList<>();
        JsonNode n = parseJson(json);
        if (n != null && n.isArray()) {
            n.forEach(x -> {
                if (x != null && x.isTextual() && StringUtils.hasText(x.asText())) {
                    out.add(x.asText().trim());
                }
            });
        }
        return out;
    }

    public record UpsertCommand(
            String name,
            String description,
            List<String> tags,
            JsonNode meal,
            JsonNode week,
            Integer energyKcalHint,
            List<String> allergenKeywords,
            String status) {}
}
