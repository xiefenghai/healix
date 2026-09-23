package com.healix.web.b.cockpit;

import com.healix.agent.cockpit.CockpitBriefingService;
import com.healix.agent.ocr.ObservationReportIngestService;
import com.healix.agent.ocr.ObservationReportIngestService.ConfirmCommand;
import com.healix.agent.ocr.ObservationReportIngestService.ConfirmExam;
import com.healix.agent.ocr.ObservationReportIngestService.ConfirmLab;
import com.healix.agent.ocr.ObservationReportIngestService.ConfirmLabItem;
import com.healix.agent.ocr.ObservationReportIngestService.ConfirmMed;
import com.healix.agent.ocr.ObservationReportIngestService.ConfirmMedItem;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.cockpit.dto.CockpitBriefingDto;
import com.healix.core.cockpit.dto.CockpitFocusDto;
import com.healix.core.cockpit.dto.CockpitPriorityCardDto;
import com.healix.core.cockpit.dto.CockpitSummaryDto;
import com.healix.core.cockpit.service.CockpitService;
import com.healix.core.observation.dto.ObservationOcrIngestDto;
import com.healix.core.observation.dto.ObservationOcrPreviewDto;
import com.healix.security.SecurityUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * B 端智能驾驶舱 API：摘要 / 优先名单 / 焦点快照 / 开场简报 / OCR 核对入库。
 *
 * <p>感知层只读编排；对话走 {@code /api/b/v1/agent/chat/stream}（可无 peopleId 的机构会话）。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1/cockpit")
@RequiredArgsConstructor
public class BCockpitController {

    private final CockpitService cockpitService;
    private final CockpitBriefingService cockpitBriefingService;
    private final ObservationReportIngestService observationReportIngestService;

    @GetMapping("/summary")
    public ApiResult<CockpitSummaryDto> summary() {
        return ApiResult.ok(cockpitService.summary(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId()));
    }

    @GetMapping("/priority")
    public ApiResult<List<CockpitPriorityCardDto>> priority(
            @RequestParam(required = false, defaultValue = "urgent") String tab) {
        return ApiResult.ok(cockpitService.priority(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), tab));
    }

    /**
     * 我的患者：当前员工作为主责健管师的健管组下患者列表（可 keyword 筛选）。
     * 返回结构与 /priority 优先卡一致，便于左栏三 Tab 共用同一套卡片。
     */
    @GetMapping("/my-patients")
    public ApiResult<List<CockpitPriorityCardDto>> myPatients(
            @RequestParam(required = false) String keyword) {
        return ApiResult.ok(cockpitService.myPatients(SecurityUtils.requireCurrentOrgId(), keyword));
    }

    @GetMapping("/focus/{peopleId}")
    public ApiResult<CockpitFocusDto> focus(@PathVariable String peopleId) {
        return ApiResult.ok(cockpitService.focus(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @GetMapping("/briefing")
    public ApiResult<CockpitBriefingDto> briefing() {
        return ApiResult.ok(cockpitBriefingService.briefing(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                false));
    }

    /** 上传检查/检验单：只识别预览，不落库。 */
    @PostMapping(value = "/patients/{peopleId}/reports/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ObservationOcrPreviewDto> recognizeReport(
            @PathVariable String peopleId, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传检查或检验单图片");
        }
        try {
            return ApiResult.ok(observationReportIngestService.recognize(
                    SecurityUtils.requireTenantId(),
                    SecurityUtils.requireCurrentOrgId(),
                    peopleId,
                    file.getBytes(),
                    file.getContentType()));
        } catch (java.io.IOException e) {
            throw new BusinessException("读取图片失败");
        }
    }

    /** 人工核对后确认入库。 */
    @PostMapping("/patients/{peopleId}/reports/confirm")
    public ApiResult<ObservationOcrIngestDto> confirmReport(
            @PathVariable String peopleId, @RequestBody ConfirmRequest request) {
        if (request == null) {
            throw new BusinessException("请提交核对后的识别结果");
        }
        ConfirmLab lab = null;
        if (request.lab() != null) {
            List<ConfirmLabItem> items = request.lab().items() == null
                    ? List.of()
                    : request.lab().items().stream()
                            .map(i -> new ConfirmLabItem(
                                    i.itemCode(),
                                    i.itemName(),
                                    i.valueNum(),
                                    i.valueText(),
                                    i.unit(),
                                    i.refLow(),
                                    i.refHigh(),
                                    i.abnormalFlag()))
                            .toList();
            lab = new ConfirmLab(
                    request.lab().specimenType(),
                    request.lab().sampledAt(),
                    request.lab().reportedAt(),
                    request.lab().note(),
                    items);
        }
        ConfirmExam exam = null;
        if (request.exam() != null) {
            exam = new ConfirmExam(
                    request.exam().examType(),
                    request.exam().examTypeName(),
                    request.exam().examinedAt(),
                    request.exam().conclusion(),
                    request.exam().findings());
        }
        ConfirmMed med = null;
        if (request.med() != null) {
            List<ConfirmMedItem> medItems = request.med().items() == null
                    ? List.of()
                    : request.med().items().stream()
                            .map(i -> new ConfirmMedItem(
                                    i.drugName(),
                                    i.usageMethod(),
                                    i.frequency(),
                                    i.doseAmount(),
                                    i.doseUnit(),
                                    i.timingNote(),
                                    i.courseDays(),
                                    i.startDate()))
                            .toList();
            med = new ConfirmMed(medItems);
        }
        return ApiResult.ok(observationReportIngestService.confirm(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                new ConfirmCommand(request.kind(), lab, exam, med)));
    }

    /** 兼容旧路径：等同 recognize（不再自动入库）。 */
    @PostMapping(value = "/patients/{peopleId}/reports/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ObservationOcrPreviewDto> ingestReportCompat(
            @PathVariable String peopleId, @RequestParam("file") MultipartFile file) {
        return recognizeReport(peopleId, file);
    }

    @PostMapping("/briefing/refresh")
    public ApiResult<CockpitBriefingDto> refreshBriefing() {
        return ApiResult.ok(cockpitBriefingService.briefing(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                true));
    }

    public record ConfirmRequest(String kind, ConfirmLabBody lab, ConfirmExamBody exam, ConfirmMedBody med) {}

    public record ConfirmLabBody(
            String specimenType,
            LocalDateTime sampledAt,
            LocalDateTime reportedAt,
            String note,
            List<ConfirmLabItemBody> items) {}

    public record ConfirmLabItemBody(
            String itemCode,
            String itemName,
            BigDecimal valueNum,
            String valueText,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String abnormalFlag) {}

    public record ConfirmExamBody(
            String examType,
            String examTypeName,
            LocalDateTime examinedAt,
            String conclusion,
            Map<String, Object> findings) {}

    public record ConfirmMedBody(List<ConfirmMedItemBody> items) {}

    public record ConfirmMedItemBody(
            String drugName,
            String usageMethod,
            String frequency,
            String doseAmount,
            String doseUnit,
            String timingNote,
            Integer courseDays,
            LocalDate startDate) {}
}
