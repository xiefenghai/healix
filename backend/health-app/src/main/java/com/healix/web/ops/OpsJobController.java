package com.healix.web.ops;

import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.job.dto.JobCatalogItemDto;
import com.healix.core.job.dto.JobDefViewDto;
import com.healix.core.job.dto.JobRunViewDto;
import com.healix.core.job.service.JobAdminService;
import com.healix.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ops 平台定时任务：列表、启停/改 Cron、整轮手动触发、执行日志。 */
@Validated
@RestController
@RequestMapping("/api/ops/v1/jobs")
@RequiredArgsConstructor
public class OpsJobController {

    private final JobAdminService jobAdminService;

    @GetMapping
    public ApiResult<List<JobDefViewDto>> list() {
        SecurityUtils.requireContext();
        return ApiResult.ok(jobAdminService.list());
    }

    @GetMapping("/catalog")
    public ApiResult<List<JobCatalogItemDto>> catalog() {
        SecurityUtils.requireContext();
        return ApiResult.ok(jobAdminService.listCatalog());
    }

    @PostMapping
    public ApiResult<JobDefViewDto> create(@RequestBody @Valid CreateJobRequest request) {
        SecurityUtils.requireContext();
        return ApiResult.ok(jobAdminService.create(request.jobCode(), request.enabled(), request.cronExpr()));
    }

    @PutMapping("/{jobCode}")
    public ApiResult<JobDefViewDto> update(
            @PathVariable String jobCode, @RequestBody @Validated UpdateJobRequest request) {
        SecurityUtils.requireContext();
        return ApiResult.ok(jobAdminService.update(jobCode, request.enabled(), request.cronExpr()));
    }

    @PostMapping("/{jobCode}/run")
    public ApiResult<JobRunViewDto> run(@PathVariable @NotBlank String jobCode) {
        SecurityUtils.requireContext();
        return ApiResult.ok(jobAdminService.runNow(jobCode));
    }

    @GetMapping("/{jobCode}/runs")
    public ApiResult<PageResult<JobRunViewDto>> runs(
            @PathVariable String jobCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        SecurityUtils.requireContext();
        return ApiResult.ok(jobAdminService.listRuns(jobCode, page, pageSize));
    }

    public record CreateJobRequest(@NotBlank String jobCode, Boolean enabled, String cronExpr) {}

    public record UpdateJobRequest(Boolean enabled, String cronExpr) {}
}
