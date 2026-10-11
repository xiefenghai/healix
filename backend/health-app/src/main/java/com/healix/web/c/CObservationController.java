package com.healix.web.c;

import com.healix.agent.ocr.ExamOcrRecognitionService;
import com.healix.agent.ocr.LabOcrRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.observation.dto.ExamOcrPrefillDto;
import com.healix.core.observation.dto.ExamReportViewDto;
import com.healix.core.observation.dto.LabOcrPrefillDto;
import com.healix.core.observation.dto.LabReportViewDto;
import com.healix.core.observation.dto.MetricLatestSlotDto;
import com.healix.core.observation.dto.MetricViewDto;
import com.healix.core.observation.service.ExamReportService;
import com.healix.core.observation.service.ExamReportService.ExamCommand;
import com.healix.core.observation.service.LabReportService;
import com.healix.core.observation.service.LabReportService.LabItemCommand;
import com.healix.core.observation.service.LabReportService.LabReportCommand;
import com.healix.core.observation.service.MetricService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import com.healix.core.observation.service.MetricService.MetricCommand;

/** C 端健康数据：检验/检查可上报与修改自报数据；体征趋势与录入；体征也可改正自报记录。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me")
@RequiredArgsConstructor
public class CObservationController {

    private final LabReportService labReportService;
    private final ExamReportService examReportService;
    private final MetricService metricService;
    private final LabOcrRecognitionService labOcrRecognitionService;
    private final ExamOcrRecognitionService examOcrRecognitionService;

    @GetMapping("/metrics/latest")
    public ApiResult<List<MetricLatestSlotDto>> metricsLatest() {
        return ApiResult.ok(metricService.latestForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    /** 指标历史列表，供 C 端趋势图使用。 */
    @GetMapping("/metrics")
    public ApiResult<List<MetricViewDto>> metrics(
            @RequestParam(required = false) String metricType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(metricService.listForPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                metricType,
                from,
                to,
                limit));
    }

    /** 用户改正自己上报的指标（仅 SELF/PATIENT）。 */
    @PutMapping("/metrics/{metricId}")
    public ApiResult<MetricViewDto> updateMetric(
            @PathVariable String metricId, @RequestBody @Validated UpdateMetricRequest request) {
        MetricCommand cmd = new MetricCommand(
                request.metricType(),
                request.value(),
                request.unit(),
                request.recordedAt(),
                request.bpContext(),
                request.mealContext(),
                request.note(),
                null);
        return ApiResult.ok(metricService.updateForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), metricId, cmd));
    }

    /** 用户删除自己上报的指标；血压成对传 group=true。 */
    @DeleteMapping("/metrics/{metricId}")
    public ApiResult<Void> deleteMetric(
            @PathVariable String metricId, @RequestParam(required = false, defaultValue = "false") boolean group) {
        metricService.deleteForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), metricId, group);
        return ApiResult.ok(null);
    }

    @GetMapping("/labs")
    public ApiResult<List<LabReportViewDto>> labs() {
        return ApiResult.ok(labReportService.listForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    /**
     * 检验单 OCR（患者端）。
     * <p>本期先开放，后续患者权益再做次数/套餐权限控制。
     */
    @PostMapping(value = "/labs/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<LabOcrPrefillDto> labOcr(@RequestParam("file") MultipartFile file) {
        SecurityUtils.requirePatientId();
        SecurityUtils.requireTenantId();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传检验单图片");
        }
        try {
            return ApiResult.ok(labOcrRecognitionService.recognize(file.getBytes(), file.getContentType()));
        } catch (java.io.IOException e) {
            throw new BusinessException("读取图片失败");
        }
    }

    /** 患者自行上报检验报告（source：PATIENT / PATIENT_OCR）。 */
    @PostMapping("/labs")
    public ApiResult<LabReportViewDto> createLab(@RequestBody @Validated CreateLabRequest request) {
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
        LabReportCommand cmd = new LabReportCommand(
                request.specimenType(),
                request.sampledAt(),
                request.reportedAt(),
                request.note(),
                request.source(),
                items);
        return ApiResult.ok(labReportService.createForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), cmd));
    }

    @GetMapping("/labs/{reportId}")
    public ApiResult<LabReportViewDto> labDetail(@PathVariable String reportId) {
        return ApiResult.ok(labReportService.getForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), reportId));
    }

    @PutMapping("/labs/{reportId}")
    public ApiResult<LabReportViewDto> updateLab(
            @PathVariable String reportId, @RequestBody @Validated CreateLabRequest request) {
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
        LabReportCommand cmd = new LabReportCommand(
                request.specimenType(),
                request.sampledAt(),
                request.reportedAt(),
                request.note(),
                request.source(),
                items);
        return ApiResult.ok(labReportService.updateForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), reportId, cmd));
    }

    @DeleteMapping("/labs/{reportId}")
    public ApiResult<Void> deleteLab(@PathVariable String reportId) {
        labReportService.deleteForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), reportId);
        return ApiResult.ok(null);
    }

    @GetMapping("/exams")
    public ApiResult<List<ExamReportViewDto>> exams(@RequestParam(required = false) String examType) {
        return ApiResult.ok(examReportService.listForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), examType));
    }

    /**
     * 检查报告 OCR（患者端）。
     * <p>与检验单一致：只返回预填，仍需用户确认后调 POST /exams 落库。
     */
    @PostMapping(value = "/exams/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ExamOcrPrefillDto> examOcr(@RequestParam("file") MultipartFile file) {
        SecurityUtils.requirePatientId();
        SecurityUtils.requireTenantId();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传检查报告图片");
        }
        try {
            return ApiResult.ok(examOcrRecognitionService.recognize(file.getBytes(), file.getContentType()));
        } catch (java.io.IOException e) {
            throw new BusinessException("读取图片失败");
        }
    }

    /** 患者自行上报检查报告（可先走 OCR 预填）。 */
    @PostMapping("/exams")
    public ApiResult<ExamReportViewDto> createExam(@RequestBody @Validated CreateExamRequest request) {
        ExamCommand cmd = new ExamCommand(
                request.examType(), request.examinedAt(), request.conclusion(), request.findings());
        return ApiResult.ok(examReportService.createForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), cmd));
    }

    @GetMapping("/exams/{examId}")
    public ApiResult<ExamReportViewDto> examDetail(@PathVariable String examId) {
        return ApiResult.ok(examReportService.getForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), examId));
    }

    @PutMapping("/exams/{examId}")
    public ApiResult<ExamReportViewDto> updateExam(
            @PathVariable String examId, @RequestBody @Validated CreateExamRequest request) {
        ExamCommand cmd = new ExamCommand(
                request.examType(), request.examinedAt(), request.conclusion(), request.findings());
        return ApiResult.ok(examReportService.updateForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), examId, cmd));
    }

    @DeleteMapping("/exams/{examId}")
    public ApiResult<Void> deleteExam(@PathVariable String examId) {
        examReportService.deleteForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), examId);
        return ApiResult.ok(null);
    }

    public record CreateLabRequest(
            String specimenType,
            LocalDateTime sampledAt,
            LocalDateTime reportedAt,
            String note,
            String source,
            @NotEmpty List<CreateLabItemRequest> items) {}

    public record CreateLabItemRequest(
            String itemCode,
            String itemName,
            BigDecimal valueNum,
            String valueText,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String abnormalFlag) {}

    public record CreateExamRequest(
            @NotBlank String examType,
            LocalDateTime examinedAt,
            String conclusion,
            Map<String, Object> findings) {}

    public record UpdateMetricRequest(
            @NotBlank String metricType,
            BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String bpContext,
            String mealContext,
            String note) {}
}
