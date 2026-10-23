package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.science.dto.ScienceViews.Overview;
import com.healix.core.science.dto.ScienceViews.Today;
import com.healix.core.science.service.ScienceService;
import com.healix.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端今日科普。 */
@RestController
@RequestMapping("/api/c/v1/science")
@RequiredArgsConstructor
public class CScienceController {

    private final ScienceService scienceService;

    @GetMapping
    public ApiResult<Overview> overview() {
        return ApiResult.ok(
                scienceService.overview(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/today")
    public ApiResult<Today> today() {
        return ApiResult.ok(
                scienceService.today(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @PostMapping("/open")
    public ApiResult<Today> open() {
        return ApiResult.ok(
                scienceService.open(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @PostMapping("/watch")
    public ApiResult<Today> watch() {
        return ApiResult.ok(
                scienceService.watch(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }
}
