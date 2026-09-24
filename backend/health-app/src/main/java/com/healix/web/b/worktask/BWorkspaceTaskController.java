package com.healix.web.b.worktask;

import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.worktask.dto.WorkspaceTaskDetailDto;
import com.healix.core.worktask.dto.WorkspaceTaskListItemDto;
import com.healix.core.worktask.dto.WorkspaceTaskSummaryDto;
import com.healix.core.worktask.service.WorkspaceTaskService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/b/v1/workspace/tasks")
@RequiredArgsConstructor
public class BWorkspaceTaskController {

    private final WorkspaceTaskService workspaceTaskService;

    @GetMapping("/summary")
    public ApiResult<WorkspaceTaskSummaryDto> summary() {
        return ApiResult.ok(workspaceTaskService.summary(SecurityUtils.requireCurrentOrgId()));
    }

    @GetMapping
    public ApiResult<PageResult<WorkspaceTaskListItemDto>> list(
            @RequestParam(required = false, defaultValue = "PUBLIC") String pool,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(workspaceTaskService.page(
                SecurityUtils.requireCurrentOrgId(),
                pool,
                taskType,
                status,
                careTeamId,
                keyword,
                page,
                size));
    }

    @GetMapping("/{id}")
    public ApiResult<WorkspaceTaskDetailDto> detail(@PathVariable String id) {
        return ApiResult.ok(workspaceTaskService.get(SecurityUtils.requireCurrentOrgId(), id));
    }

    @PostMapping("/{id}/claim")
    public ApiResult<WorkspaceTaskDetailDto> claim(@PathVariable String id) {
        return ApiResult.ok(workspaceTaskService.claim(
                SecurityUtils.requireCurrentOrgId(), id, SecurityUtils.requireContext().getAccountId()));
    }

    @PostMapping("/{id}/assign")
    public ApiResult<WorkspaceTaskDetailDto> assign(
            @PathVariable String id, @RequestBody @Validated AssignRequest request) {
        return ApiResult.ok(workspaceTaskService.assign(
                SecurityUtils.requireCurrentOrgId(),
                id,
                request.staffId(),
                SecurityUtils.requireContext().getAccountId()));
    }

    @PostMapping("/{id}/release")
    public ApiResult<WorkspaceTaskDetailDto> release(
            @PathVariable String id, @RequestBody @Validated ReasonRequest request) {
        return ApiResult.ok(workspaceTaskService.release(
                SecurityUtils.requireCurrentOrgId(),
                id,
                request.reason(),
                SecurityUtils.requireContext().getAccountId()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<WorkspaceTaskDetailDto> cancel(
            @PathVariable String id, @RequestBody @Validated ReasonRequest request) {
        return ApiResult.ok(workspaceTaskService.cancel(
                SecurityUtils.requireCurrentOrgId(),
                id,
                request.reason(),
                SecurityUtils.requireContext().getAccountId()));
    }

    @PostMapping("/{id}/forms")
    public ApiResult<WorkspaceTaskDetailDto> submitForm(
            @PathVariable String id, @RequestBody Map<String, Object> content) {
        return ApiResult.ok(workspaceTaskService.submitForm(
                SecurityUtils.requireCurrentOrgId(),
                id,
                content,
                SecurityUtils.requireContext().getAccountId()));
    }

    /** 保存随访填单草稿（不关任务） */
    @PostMapping("/{id}/forms/draft")
    public ApiResult<WorkspaceTaskDetailDto> saveFormDraft(
            @PathVariable String id, @RequestBody Map<String, Object> content) {
        return ApiResult.ok(workspaceTaskService.saveFormDraft(
                SecurityUtils.requireCurrentOrgId(),
                id,
                content,
                SecurityUtils.requireContext().getAccountId()));
    }

    public record AssignRequest(@NotBlank String staffId) {}

    public record ReasonRequest(@NotBlank String reason) {}
}
