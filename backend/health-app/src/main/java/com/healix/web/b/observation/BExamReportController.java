package com.healix.web.b.observation;

import com.healix.agent.ocr.ExamOcrRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.observation.dto.ExamOcrPrefillDto;
import com.healix.core.observation.dto.ExamReportViewDto;
import com.healix.core.observation.service.ExamReportService;
import com.healix.core.observation.service.ExamReportService.ExamCommand;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/exam-reports")
@RequiredArgsConstructor
public class BExamReportController {

    private final ExamReportService examReportService;
    private final ExamOcrRecognitionService examOcrRecognitionService;
    private final ArchiveAccessService archiveAccessService;

    @GetMapping
    public ApiResult<List<ExamReportViewDto>> list(
            @PathVariable String peopleId, @RequestParam(required = false) String examType) {
        return ApiResult.ok(examReportService.list(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                examType));
    }

    @GetMapping("/{id}")
    public ApiResult<ExamReportViewDto> get(@PathVariable String peopleId, @PathVariable String id) {
        return ApiResult.ok(examReportService.get(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId, id));
    }

    /** 检查报告 OCR：只返回预填结果，仍需人工确认后调 create 落库。 */
    @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ExamOcrPrefillDto> ocr(
            @PathVariable String peopleId, @RequestParam("file") MultipartFile file) {
        archiveAccessService.assertStaffCanAccessPeople(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传检查报告图片");
        }
        try {
            return ApiResult.ok(examOcrRecognitionService.recognize(file.getBytes(), file.getContentType()));
        } catch (java.io.IOException e) {
            throw new BusinessException("读取图片失败");
        }
    }

    @PostMapping
    public ApiResult<ExamReportViewDto> create(
            @PathVariable String peopleId, @RequestBody @Validated ExamRequest request) {
        return ApiResult.ok(examReportService.create(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                toCommand(request)));
    }

    @PutMapping("/{id}")
    public ApiResult<ExamReportViewDto> update(
            @PathVariable String peopleId,
            @PathVariable String id,
            @RequestBody @Validated ExamRequest request) {
        return ApiResult.ok(examReportService.update(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                id,
                toCommand(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String peopleId, @PathVariable String id) {
        examReportService.delete(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                id);
        return ApiResult.ok(null);
    }

    private ExamCommand toCommand(ExamRequest request) {
        return new ExamCommand(request.examType(), request.examinedAt(), request.conclusion(), request.findings());
    }

    public record ExamRequest(
            @NotBlank String examType,
            LocalDateTime examinedAt,
            String conclusion,
            Map<String, Object> findings) {}
}
