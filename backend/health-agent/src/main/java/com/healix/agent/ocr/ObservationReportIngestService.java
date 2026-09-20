package com.healix.agent.ocr;

import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.support.AiUsageGuard;
import com.healix.common.exception.BusinessException;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.observation.dto.ExamOcrPrefillDto;
import com.healix.core.observation.dto.ExamReportViewDto;
import com.healix.core.observation.dto.LabOcrPrefillDto;
import com.healix.core.observation.dto.LabOcrPrefillDto.LabOcrItemDto;
import com.healix.core.observation.dto.LabReportViewDto;
import com.healix.core.observation.dto.ObservationOcrIngestDto;
import com.healix.core.observation.dto.ObservationOcrPreviewDto;
import com.healix.core.observation.service.ExamReportService;
import com.healix.core.observation.service.ExamReportService.ExamCommand;
import com.healix.core.observation.service.LabReportService;
import com.healix.core.observation.service.LabReportService.LabItemCommand;
import com.healix.core.observation.service.LabReportService.LabReportCommand;
import com.healix.core.observation.support.ExamTypeCatalog;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 驾驶舱上传检查/检验单：识别 → 人工核对 → 确认后入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservationReportIngestService {

    private static final int CLASSIFY_TEXT_LIMIT = 2500;

    private final OcrRunner ocrRunner;
    private final OcrTextProvider ocrTextProvider;
    private final AiUsageGuard aiUsageGuard;
    private final LabOcrRecognitionService labOcrRecognitionService;
    private final ExamOcrRecognitionService examOcrRecognitionService;
    private final LabReportService labReportService;
    private final ExamReportService examReportService;
    private final ArchiveAccessService archiveAccessService;
    private final LlmClient llmClient;

    /** 仅识别，不落库。 */
    public ObservationOcrPreviewDto recognize(
            String tenantId, String orgId, String peopleId, byte[] imageBytes, String mimeType) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        ocrRunner.validateImage(imageBytes, mimeType);
        aiUsageGuard.check(tenantId, FeatureFlagKeyEnum.AI_OCR, QuotaKeyEnum.OCR_MONTHLY);
        String text = ocrTextProvider.extractText(imageBytes, mimeType);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException("未识别到单据文字，请换一张更清晰的图片");
        }

        Kind kind = classify(text);
        log.info("[OCR] preview peopleId={} kind={}", peopleId, kind);
        if (kind == Kind.LAB) {
            LabOcrPrefillDto lab = labOcrRecognitionService.recognizePrepared(imageBytes, mimeType, text);
            if (canSave(lab)) {
                return labPreview(lab);
            }
            ExamOcrPrefillDto exam = examOcrRecognitionService.recognizePrepared(imageBytes, mimeType, text);
            if (canSave(exam)) {
                return examPreview(exam);
            }
            throw cannotSave(lab, exam);
        }

        ExamOcrPrefillDto exam = examOcrRecognitionService.recognizePrepared(imageBytes, mimeType, text);
        if (canSave(exam)) {
            return examPreview(exam);
        }
        LabOcrPrefillDto lab = labOcrRecognitionService.recognizePrepared(imageBytes, mimeType, text);
        if (canSave(lab)) {
            return labPreview(lab);
        }
        throw cannotSave(lab, exam);
    }

    /** 人工确认后写入健康数据。 */
    public ObservationOcrIngestDto confirm(
            String tenantId, String orgId, String peopleId, String staffId, ConfirmCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        if (cmd == null || !StringUtils.hasText(cmd.kind())) {
            throw new BusinessException("请指定单据类型");
        }
        String kind = cmd.kind().trim().toUpperCase(Locale.ROOT);
        if ("LAB".equals(kind)) {
            return saveLab(tenantId, orgId, peopleId, staffId, toLabPrefill(cmd));
        }
        if ("EXAM".equals(kind)) {
            return saveExam(tenantId, orgId, peopleId, staffId, toExamPrefill(cmd));
        }
        throw new BusinessException("不支持的单据类型: " + cmd.kind());
    }

    private ObservationOcrPreviewDto labPreview(LabOcrPrefillDto lab) {
        return new ObservationOcrPreviewDto("LAB", "检验报告", warnings(lab.warnings()), lab, null);
    }

    private ObservationOcrPreviewDto examPreview(ExamOcrPrefillDto exam) {
        String title = StringUtils.hasText(exam.examTypeName())
                ? exam.examTypeName()
                : ExamTypeCatalog.label(exam.examType());
        return new ObservationOcrPreviewDto("EXAM", title, warnings(exam.warnings()), null, exam);
    }

    private ObservationOcrIngestDto saveLab(
            String tenantId, String orgId, String peopleId, String staffId, LabOcrPrefillDto prefill) {
        if (!canSave(prefill)) {
            throw new BusinessException("请至少确认一项检验结果");
        }
        List<LabItemCommand> items = prefill.items().stream()
                .filter(i -> i != null
                        && StringUtils.hasText(i.itemCode())
                        && (i.valueNum() != null || StringUtils.hasText(i.valueText())))
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
        if (items.isEmpty()) {
            throw new BusinessException("请至少确认一项检验结果");
        }
        LabReportViewDto saved = labReportService.create(
                tenantId,
                orgId,
                peopleId,
                staffId,
                new LabReportCommand(
                        prefill.specimenType(),
                        prefill.sampledAt(),
                        prefill.reportedAt(),
                        prefill.note(),
                        "OCR",
                        items));
        return new ObservationOcrIngestDto(
                "LAB",
                saved.id(),
                "检验报告",
                "已确认写入检验数据，共 " + items.size() + " 项。请到健康数据中继续核对。",
                warnings(prefill.warnings()));
    }

    private ObservationOcrIngestDto saveExam(
            String tenantId, String orgId, String peopleId, String staffId, ExamOcrPrefillDto prefill) {
        if (!canSave(prefill)) {
            throw new BusinessException("请补全检查类型，并确认结论或关键测量");
        }
        ExamReportViewDto saved = examReportService.createFromStaffOcr(
                tenantId,
                orgId,
                peopleId,
                staffId,
                new ExamCommand(
                        prefill.examType(), prefill.examinedAt(), prefill.conclusion(), prefill.findings()));
        String title = ExamTypeCatalog.label(prefill.examType());
        return new ObservationOcrIngestDto(
                "EXAM", saved.id(), title, "已确认写入检查数据：" + title + "。请到健康数据中继续核对。", warnings(prefill.warnings()));
    }

    private static LabOcrPrefillDto toLabPrefill(ConfirmCommand cmd) {
        if (cmd.lab() == null) {
            throw new BusinessException("检验识别结果不能为空");
        }
        ConfirmLab lab = cmd.lab();
        List<LabOcrItemDto> items = lab.items() == null
                ? List.of()
                : lab.items().stream()
                        .map(i -> new LabOcrItemDto(
                                i.itemCode(),
                                i.itemName(),
                                i.valueNum(),
                                i.valueText(),
                                i.unit(),
                                i.refLow(),
                                i.refHigh(),
                                i.abnormalFlag()))
                        .toList();
        return new LabOcrPrefillDto(
                lab.specimenType(), lab.sampledAt(), lab.reportedAt(), lab.note(), items, List.of(), List.of());
    }

    private static ExamOcrPrefillDto toExamPrefill(ConfirmCommand cmd) {
        if (cmd.exam() == null) {
            throw new BusinessException("检查识别结果不能为空");
        }
        ConfirmExam exam = cmd.exam();
        return new ExamOcrPrefillDto(
                exam.examType(),
                exam.examTypeName(),
                exam.examinedAt(),
                exam.conclusion(),
                exam.findings() == null ? Map.of() : exam.findings(),
                List.of(),
                List.of());
    }

    private static boolean canSave(LabOcrPrefillDto prefill) {
        return prefill != null && prefill.items() != null && !prefill.items().isEmpty();
    }

    private static boolean canSave(ExamOcrPrefillDto prefill) {
        if (prefill == null || !StringUtils.hasText(prefill.examType())) {
            return false;
        }
        boolean hasFindings = prefill.findings() != null && !prefill.findings().isEmpty();
        return hasFindings || StringUtils.hasText(prefill.conclusion());
    }

    private static BusinessException cannotSave(LabOcrPrefillDto lab, ExamOcrPrefillDto exam) {
        List<String> parts = new ArrayList<>();
        parts.add("未能从图片中提取可写入的检查或检验数据");
        if (exam != null && !StringUtils.hasText(exam.examType())) {
            parts.add("检查类型未识别");
        }
        if (lab != null && lab.warnings() != null) {
            parts.addAll(lab.warnings());
        }
        if (exam != null && exam.warnings() != null) {
            parts.addAll(exam.warnings());
        }
        return new BusinessException(String.join("。", parts));
    }

    private Kind classify(String text) {
        String compact = text.replaceAll("\\s+", "");
        int lab = hits(
                compact,
                "检验报告",
                "化验单",
                "检验科",
                "参考区间",
                "参考范围",
                "标本",
                "白细胞",
                "血红蛋白",
                "血小板",
                "尿常规");
        int exam = hits(
                compact,
                "超声",
                "彩超",
                "B超",
                "检查所见",
                "影像诊断",
                "心电图",
                "放射",
                "心电",
                "检查报告",
                "CT",
                "MRI",
                "核磁");
        if (lab >= 2 && lab > exam) {
            return Kind.LAB;
        }
        if (exam >= 2 && exam > lab) {
            return Kind.EXAM;
        }
        Kind guessed = llmClassify(compact);
        if (guessed != null) {
            return guessed;
        }
        return lab >= exam ? Kind.LAB : Kind.EXAM;
    }

    private Kind llmClassify(String text) {
        if (!llmClient.isEnabled()) {
            return null;
        }
        String snippet = text.length() > CLASSIFY_TEXT_LIMIT ? text.substring(0, CLASSIFY_TEXT_LIMIT) : text;
        LlmResponse response = llmClient.chat(
                "ocr-kind",
                "你只回答 LAB 或 EXAM。检验/化验单回答 LAB，超声/影像/心电等检查报告回答 EXAM。不要解释。",
                snippet,
                List.of());
        if (!response.fromLlm() || !StringUtils.hasText(response.content())) {
            return null;
        }
        String answer = response.content().trim().toUpperCase(Locale.ROOT);
        if (answer.contains("EXAM")) {
            return Kind.EXAM;
        }
        if (answer.contains("LAB")) {
            return Kind.LAB;
        }
        return null;
    }

    private static int hits(String text, String... keys) {
        String upper = text.toUpperCase(Locale.ROOT);
        int n = 0;
        for (String key : keys) {
            if (upper.contains(key.toUpperCase(Locale.ROOT))) {
                n++;
            }
        }
        return n;
    }

    private static List<String> warnings(List<String> warnings) {
        return warnings == null ? List.of() : List.copyOf(warnings);
    }

    private enum Kind {
        LAB,
        EXAM
    }

    public record ConfirmCommand(String kind, ConfirmLab lab, ConfirmExam exam) {}

    public record ConfirmLab(
            String specimenType,
            LocalDateTime sampledAt,
            LocalDateTime reportedAt,
            String note,
            List<ConfirmLabItem> items) {}

    public record ConfirmLabItem(
            String itemCode,
            String itemName,
            BigDecimal valueNum,
            String valueText,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String abnormalFlag) {}

    public record ConfirmExam(
            String examType,
            String examTypeName,
            LocalDateTime examinedAt,
            String conclusion,
            Map<String, Object> findings) {}
}
