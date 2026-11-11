package com.healix.web.b.observation;

import com.healix.agent.ocr.LabOcrRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.observation.dto.LabOcrPrefillDto;
import com.healix.core.observation.dto.LabReportViewDto;
import com.healix.core.observation.service.LabReportService;
import com.healix.core.observation.service.LabReportService.LabItemCommand;
import com.healix.core.observation.service.LabReportService.LabReportCommand;
import com.healix.core.observation.service.LabReportService.OcrDiffItemPayload;
import com.healix.core.observation.service.LabReportService.OcrDiffLogPayload;
import com.healix.core.storage.ImageFileService;
import com.healix.core.storage.ObjectKeys;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
@RequestMapping("/api/b/v1/patients/{peopleId}/lab-reports")
@RequiredArgsConstructor
public class BLabReportController {

    private final LabReportService labReportService;
    private final LabOcrRecognitionService labOcrRecognitionService;
    private final ArchiveAccessService archiveAccessService;
    private final ImageFileService imageFileService;

    @GetMapping
    public ApiResult<List<LabReportViewDto>> list(@PathVariable String peopleId) {
        return ApiResult.ok(labReportService.list(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @GetMapping("/{reportId}")
    public ApiResult<LabReportViewDto> get(@PathVariable String peopleId, @PathVariable String reportId) {
        return ApiResult.ok(labReportService.get(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                reportId));
    }

    @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<LabOcrPrefillDto> ocr(
            @PathVariable String peopleId, @RequestParam("file") MultipartFile file) {
        archiveAccessService.assertStaffCanAccessPeople(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId);
        try {
            byte[] bytes = file.getBytes();
            LabOcrPrefillDto prefill = labOcrRecognitionService.recognize(bytes, file.getContentType());
            String key = imageFileService.save(
                    SecurityUtils.requireTenantId(), peopleId, ObjectKeys.LAB, bytes, file.getContentType());
            return ApiResult.ok(prefill.withImageKey(key));
        } catch (java.io.IOException e) {
            throw new BusinessException("读取图片失败");
        }
    }

    @PostMapping
    public ApiResult<LabReportViewDto> create(
            @PathVariable String peopleId, @RequestBody @Validated LabReportRequest request) {
        return ApiResult.ok(labReportService.create(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                toCommand(request)));
    }

    @PutMapping("/{reportId}")
    public ApiResult<LabReportViewDto> update(
            @PathVariable String peopleId,
            @PathVariable String reportId,
            @RequestBody @Validated LabReportRequest request) {
        return ApiResult.ok(labReportService.update(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                reportId,
                toCommand(request)));
    }

    @DeleteMapping("/{reportId}")
    public ApiResult<Void> delete(@PathVariable String peopleId, @PathVariable String reportId) {
        labReportService.delete(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                reportId);
        return ApiResult.ok(null);
    }

    private LabReportCommand toCommand(LabReportRequest request) {
        List<LabItemCommand> items = request.items().stream()
                .map(i -> new LabItemCommand(
                        i.itemCode(),
                        i.itemName(),
                        i.valueNum(),
                        i.valueText(),
                        i.unit(),
                        i.refLow(),
                        i.refHigh(),
                        i.abnormalFlag()))
                .toList();
        OcrDiffLogPayload diff = null;
        if (request.ocrDiffLog() != null && request.ocrDiffLog().items() != null) {
            List<OcrDiffItemPayload> diffItems = request.ocrDiffLog().items().stream()
                    .filter(i -> i != null && i.itemCode() != null && !i.itemCode().isBlank())
                    .map(i -> new OcrDiffItemPayload(
                            i.itemCode(),
                            i.itemName(),
                            i.aiValueNum(),
                            i.aiValueText(),
                            i.finalValueNum(),
                            i.finalValueText(),
                            i.changed()))
                    .toList();
            diff = new OcrDiffLogPayload(request.ocrDiffLog().imageKey(), diffItems);
        }
        return new LabReportCommand(
                request.specimenType(),
                request.sampledAt(),
                request.reportedAt(),
                request.note(),
                request.source(),
                items,
                request.imageKey(),
                diff);
    }

    public record LabReportRequest(
            String specimenType,
            LocalDateTime sampledAt,
            LocalDateTime reportedAt,
            String note,
            String source,
            String imageKey,
            OcrDiffLogRequest ocrDiffLog,
            @NotEmpty List<LabItemRequest> items) {}

    public record LabItemRequest(
            String itemCode,
            String itemName,
            BigDecimal valueNum,
            String valueText,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String abnormalFlag) {}

    public record OcrDiffLogRequest(String imageKey, List<OcrDiffItemRequest> items) {}

    public record OcrDiffItemRequest(
            String itemCode,
            String itemName,
            BigDecimal aiValueNum,
            String aiValueText,
            BigDecimal finalValueNum,
            String finalValueText,
            boolean changed) {}
}
