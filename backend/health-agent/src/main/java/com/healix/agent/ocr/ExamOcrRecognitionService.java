package com.healix.agent.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.core.observation.dto.ExamOcrPrefillDto;
import com.healix.core.observation.support.ExamTypeCatalog;
import com.healix.core.observation.support.ExamTypeCatalog.FindingField;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 检查报告（超声/影像/心电等）OCR 预填。
 *
 * <p>产出只进人工确认页，不直接落库：结论是照抄原文，误识别的代价由健管师兜底。
 * 模型给出的 findings 会按 {@link ExamTypeCatalog} 的字段类型逐个校验，
 * 不在 schema 内或类型不符的一律丢弃并计入 ignoredFindings。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamOcrRecognitionService {

    /** 结论字段入库上限（exam_report.conclusion 为 VARCHAR(1000)） */
    private static final int MAX_CONCLUSION_CHARS = 800;

    private final OcrRunner ocrRunner;

    public ExamOcrPrefillDto recognize(byte[] imageBytes, String mimeType) {
        JsonNode root = ocrRunner.recognizeJson(
                "ocr-exam",
                structureUserPrompt(),
                imageBytes,
                mimeType,
                "你是医疗检查报告 OCR 助手，仅输出 JSON，结论照抄原文，不做诊断。",
                "检查报告识别失败，请检查图片清晰度或稍后重试");
        return toPrefill(root);
    }

    private ExamOcrPrefillDto toPrefill(JsonNode root) {
        List<String> warnings = new ArrayList<>();
        if (root.path("warnings").isArray()) {
            root.path("warnings").forEach(n -> {
                if (n.isTextual() && StringUtils.hasText(n.asText())) {
                    warnings.add(n.asText().trim());
                }
            });
        }

        String rawType = firstText(root, "examType", "examTypeName", "examName");
        Optional<String> codeOpt = ExamTypeCatalog.resolveCode(rawType);
        if (codeOpt.isEmpty()) {
            warnings.add(StringUtils.hasText(rawType)
                    ? "未能识别检查类型「" + rawType + "」，请手动选择"
                    : "未能识别检查类型，请手动选择");
        }
        String code = codeOpt.orElse(null);

        List<String> ignored = new ArrayList<>();
        Map<String, Object> findings =
                code == null ? Map.of() : extractFindings(root.path("findings"), code, ignored);
        if (!ignored.isEmpty()) {
            warnings.add("已忽略 " + ignored.size() + " 项无法对齐的测量");
        }

        String conclusion = truncate(textOrNull(root, "conclusion"));
        if (!StringUtils.hasText(conclusion) && findings.isEmpty()) {
            warnings.add("未识别到结论与关键测量，请手动补全");
        }

        return new ExamOcrPrefillDto(
                code,
                code == null ? null : ExamTypeCatalog.label(code),
                parseDateTime(firstText(root, "examinedAt", "reportedAt"), warnings),
                conclusion,
                findings,
                ignored,
                warnings);
    }

    /** 按 schema 逐字段校验；类型不符当作没识别到，避免脏值进人工确认页。 */
    private Map<String, Object> extractFindings(JsonNode node, String code, List<String> ignored) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        Map<String, FindingField> allowed = new LinkedHashMap<>();
        for (FindingField f : ExamTypeCatalog.fields(code)) {
            allowed.put(f.key(), f);
        }
        node.fieldNames().forEachRemaining(name -> {
            FindingField field = allowed.get(name);
            JsonNode value = node.get(name);
            if (field == null) {
                ignored.add(name);
                return;
            }
            if (value == null || value.isNull()) {
                return;
            }
            Object coerced = coerce(field, value);
            if (coerced == null) {
                ignored.add(name);
                return;
            }
            out.put(name, coerced);
        });
        return out;
    }

    private static Object coerce(FindingField field, JsonNode value) {
        return switch (field.type()) {
            case NUMBER -> {
                if (value.isNumber()) {
                    yield value.decimalValue();
                }
                String text = value.asText("").trim();
                if (!StringUtils.hasText(text)) {
                    yield null;
                }
                try {
                    // 报告里常写「1.2mm」「65 %」，去掉单位再解析
                    yield new java.math.BigDecimal(text.replaceAll("[^0-9.\\-]", ""));
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            case BOOL -> {
                if (value.isBoolean()) {
                    yield value.booleanValue();
                }
                String text = value.asText("").trim().toUpperCase(Locale.ROOT);
                if ("TRUE".equals(text) || "YES".equals(text) || "有".equals(text) || "1".equals(text)) {
                    yield Boolean.TRUE;
                }
                if ("FALSE".equals(text) || "NO".equals(text) || "无".equals(text) || "0".equals(text)) {
                    yield Boolean.FALSE;
                }
                yield null;
            }
            case SELECT -> {
                String text = value.asText("").trim().toUpperCase(Locale.ROOT);
                yield field.options().contains(text) ? text : null;
            }
        };
    }

    private static String structureUserPrompt() {
        return """
                请从检查报告内容中抽取结构化信息，输出严格 JSON（不要 markdown 代码块）：
                {
                  "examType": "下列枚举之一或 null",
                  "examinedAt": "检查时间 ISO-8601（如 2026-09-01T10:30:00）或 null",
                  "conclusion": "照抄报告结论原文或 null",
                  "findings": { "字段名": 值 },
                  "warnings": ["识别不清的说明"]
                }

                可用的 examType 与各自允许的 findings 字段：
                """
                + ExamTypeCatalog.describeForPrompt()
                + """

                findings 只允许出现所选 examType 对应的字段，其余一律不要输出。
                不要给出诊断结论或治疗建议。
                """;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String v = textOrNull(node, field);
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String text = v.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static String truncate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        return s.length() <= MAX_CONCLUSION_CHARS ? s : s.substring(0, MAX_CONCLUSION_CHARS);
    }

    /** 报告上常只写到日期，补 00:00 而不是丢弃。 */
    private static LocalDateTime parseDateTime(String raw, List<String> warnings) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException ignored) {
            // 继续尝试纯日期
        }
        try {
            return java.time.LocalDate.parse(s).atStartOfDay();
        } catch (DateTimeParseException e) {
            warnings.add("检查时间不清晰");
            return null;
        }
    }
}
