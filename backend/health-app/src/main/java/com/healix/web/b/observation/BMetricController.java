package com.healix.web.b.observation;

import com.healix.common.result.ApiResult;
import com.healix.core.observation.dto.MetricLatestSlotDto;
import com.healix.core.observation.dto.MetricViewDto;
import com.healix.core.observation.service.MetricService;
import com.healix.core.observation.service.MetricService.MetricCommand;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/b/v1/patients/{peopleId}/metrics")
@RequiredArgsConstructor
public class BMetricController {

    private final MetricService metricService;

    @GetMapping
    public ApiResult<List<MetricViewDto>> list(
            @PathVariable String peopleId,
            @RequestParam(required = false) String metricType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(metricService.list(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                metricType,
                from,
                to,
                limit));
    }

    @GetMapping("/latest")
    public ApiResult<List<MetricLatestSlotDto>> latest(@PathVariable String peopleId) {
        return ApiResult.ok(metricService.latest(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @PostMapping
    public ApiResult<MetricViewDto> create(
            @PathVariable String peopleId, @RequestBody @Validated MetricRequest request) {
        return ApiResult.ok(metricService.create(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                toCommand(request)));
    }

    @PostMapping("/batches")
    public ApiResult<List<MetricViewDto>> createBatch(
            @PathVariable String peopleId, @RequestBody @Validated MetricBatchRequest request) {
        List<MetricCommand> items = request.items().stream().map(this::toCommand).toList();
        return ApiResult.ok(metricService.createBatch(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                items));
    }

    @PutMapping("/{id}")
    public ApiResult<MetricViewDto> update(
            @PathVariable String peopleId,
            @PathVariable String id,
            @RequestBody @Validated MetricRequest request) {
        return ApiResult.ok(metricService.update(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                id,
                toCommand(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(
            @PathVariable String peopleId,
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean group) {
        metricService.delete(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                id,
                group);
        return ApiResult.ok(null);
    }

    private MetricCommand toCommand(MetricRequest request) {
        return new MetricCommand(
                request.metricType(),
                request.value(),
                request.unit(),
                request.recordedAt(),
                request.bpContext(),
                request.mealContext(),
                request.note(),
                request.extra());
    }

    public record MetricRequest(
            @NotBlank String metricType,
            @NotNull BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String bpContext,
            String mealContext,
            String note,
            Map<String, Object> extra) {}

    public record MetricBatchRequest(@NotEmpty List<MetricRequest> items) {}
}
