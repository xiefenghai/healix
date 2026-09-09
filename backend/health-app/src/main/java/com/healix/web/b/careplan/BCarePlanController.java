package com.healix.web.b.careplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.careplan.CarePlanAgentService;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.dto.CarePlanCheckinViewDto;
import com.healix.core.careplan.dto.CarePlanDailyCheckinSummaryDto;
import com.healix.core.careplan.dto.CarePlanListItemDto;
import com.healix.core.careplan.dto.CarePlanVersionDto;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.careplan.service.CarePlanService;
import com.healix.security.JwtAuthenticationFilter;
import com.healix.security.SecurityUtils;
import com.healix.web.support.AgentSseSupport;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BCarePlanController {

    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final CarePlanService carePlanService;
    private final CarePlanAgentService carePlanAgentService;
    private final CarePlanCheckinService checkinService;

    @GetMapping("/patients/{peopleId}/care-plan")
    public ApiResult<CarePlanBundleDto> get(@PathVariable String peopleId) {
        return ApiResult.ok(carePlanService.getBundle(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @PostMapping("/patients/{peopleId}/care-plan/draft")
    public ApiResult<CarePlanBundleDto> createBlankDraft(
            @PathVariable String peopleId, @RequestBody(required = false) ReplaceRequest request) {
        boolean replace = request != null && Boolean.TRUE.equals(request.replaceDraft());
        return ApiResult.ok(carePlanService.createBlankDraft(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                replace));
    }

    @PostMapping("/patients/{peopleId}/care-plan/generate")
    public ApiResult<CarePlanBundleDto> generate(
            @PathVariable String peopleId, @RequestBody(required = false) GenerateRequest request) {
        GenerateRequest req = request == null ? new GenerateRequest(null, null, null, true) : request;
        boolean replace = req.replaceDraft() == null || req.replaceDraft();
        return ApiResult.ok(carePlanAgentService.generate(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                req.instruction(),
                req.templateKey(),
                req.diseaseCodes(),
                replace));
    }

    @PostMapping(value = "/patients/{peopleId}/care-plan/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(
            @PathVariable String peopleId,
            @RequestBody(required = false) GenerateRequest request,
            HttpServletResponse response) {
        GenerateRequest req = request == null ? new GenerateRequest(null, null, null, true) : request;
        boolean replace = req.replaceDraft() == null || req.replaceDraft();
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        String staffId = SecurityUtils.requireStaffId();
        AgentSseSupport.prepareSseResponse(response);
        SseEmitter emitter = AgentSseSupport.createEmitter(SSE_TIMEOUT_MS);
        AgentSseSupport.openStream(emitter, response);
        CompletableFuture.runAsync(JwtAuthenticationFilter.wrapAsync(() -> {
            try {
                var sink = AgentSseSupport.sseSink(emitter, response);
                CarePlanBundleDto bundle = carePlanAgentService.generateStream(
                        tenantId,
                        orgId,
                        peopleId,
                        staffId,
                        req.instruction(),
                        req.templateKey(),
                        req.diseaseCodes(),
                        replace,
                        sink);
                sink.accept(AgentStreamEvent.result(bundle));
                sink.accept(AgentStreamEvent.done());
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(AgentStreamEvent.error(
                                            ex.getMessage() == null ? "generate failed" : ex.getMessage())
                                    .toJsonLine()));
                } catch (Exception ignored) {
                    // ignore
                }
                emitter.completeWithError(ex);
            }
        }));
        return emitter;
    }

    @PutMapping("/patients/{peopleId}/care-plan/draft")
    public ApiResult<CarePlanBundleDto> updateDraft(
            @PathVariable String peopleId, @RequestBody @Validated UpdateDraftRequest request) {
        return ApiResult.ok(carePlanService.updateDraft(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                request.version(),
                request.exercise(),
                request.diet(),
                request.execution(),
                request.title(),
                request.goalSummary()));
    }

    @DeleteMapping("/patients/{peopleId}/care-plan/draft")
    public ApiResult<CarePlanBundleDto> discardDraft(@PathVariable String peopleId) {
        return ApiResult.ok(carePlanService.discardDraft(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId()));
    }

    @PostMapping("/patients/{peopleId}/care-plan/edit")
    public ApiResult<CarePlanBundleDto> openEdit(@PathVariable String peopleId) {
        return ApiResult.ok(carePlanService.openEditFromActive(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId()));
    }

    @PostMapping("/patients/{peopleId}/care-plan/publish")
    public ApiResult<CarePlanBundleDto> publish(
            @PathVariable String peopleId, @RequestBody(required = false) PublishRequest request) {
        PublishRequest req = request == null ? new PublishRequest(List.of(), null) : request;
        return ApiResult.ok(carePlanService.publish(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                req.ackWarnCodes(),
                req.title()));
    }

    @GetMapping("/patients/{peopleId}/care-plan/items")
    public ApiResult<PageResult<CarePlanListItemDto>> listItems(
            @PathVariable String peopleId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResult.ok(carePlanService.pageListItems(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                page,
                pageSize));
    }

    @DeleteMapping("/patients/{peopleId}/care-plan/items/{itemId}")
    public ApiResult<Void> deleteItem(
            @PathVariable String peopleId,
            @PathVariable String itemId,
            @RequestParam String recordType) {
        carePlanService.deleteListItem(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                recordType,
                itemId);
        return ApiResult.ok(null);
    }

    @GetMapping("/patients/{peopleId}/care-plan/versions")
    public ApiResult<List<CarePlanVersionDto>> listVersions(@PathVariable String peopleId) {
        return ApiResult.ok(carePlanService.listVersions(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @GetMapping("/patients/{peopleId}/care-plan/versions/{versionId}")
    public ApiResult<CarePlanVersionDto> getVersion(
            @PathVariable String peopleId, @PathVariable String versionId) {
        return ApiResult.ok(carePlanService.getVersion(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                versionId));
    }

    /** 医生签署方案版本（临床复核）；开启「方案医生复核」开关后会同时关掉 PLAN_REVIEW 任务。 */
    @PostMapping("/patients/{peopleId}/care-plan/versions/{versionId}/sign")
    public ApiResult<CarePlanVersionDto> signVersion(
            @PathVariable String peopleId, @PathVariable String versionId) {
        return ApiResult.ok(carePlanService.signVersion(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                versionId,
                SecurityUtils.requireStaffId()));
    }

    @GetMapping("/patients/{peopleId}/care-plan/checkins/daily")
    public ApiResult<CarePlanDailyCheckinSummaryDto> dailyCheckins(
            @PathVariable String peopleId, @RequestParam(required = false) LocalDate date) {
        return ApiResult.ok(checkinService.getDailySummary(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                date));
    }

    @GetMapping("/patients/{peopleId}/care-plan/checkins")
    public ApiResult<List<CarePlanCheckinViewDto>> listCheckins(
            @PathVariable String peopleId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ApiResult.ok(checkinService.listCheckins(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                from,
                to));
    }

    @GetMapping("/patients/{peopleId}/care-plan/versions/{versionId}/checkins")
    public ApiResult<List<CarePlanCheckinViewDto>> listVersionCheckins(
            @PathVariable String peopleId,
            @PathVariable String versionId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ApiResult.ok(checkinService.listCheckinsByVersionForStaff(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                versionId,
                from,
                to));
    }

    public record ReplaceRequest(Boolean replaceDraft) {}

    public record GenerateRequest(
            String instruction, String templateKey, List<String> diseaseCodes, Boolean replaceDraft) {}

    public record UpdateDraftRequest(
            @NotNull Integer version,
            JsonNode exercise,
            JsonNode diet,
            JsonNode execution,
            String title,
            String goalSummary) {}

    public record PublishRequest(List<String> ackWarnCodes, String title) {}
}
