package com.healix.agent.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.core.medication.dto.MedOcrPrefillDto;
import com.healix.core.medication.dto.MedOcrPrefillDto.MedOcrItemDto;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class MedOcrRecognitionService {

    private final OcrRunner ocrRunner;

    private static final String SYSTEM_PROMPT = "你是医疗用药单 OCR 助手，仅输出 JSON，不做诊断。";
    private static final String FAILURE = "用药单识别失败，请检查图片清晰度或稍后重试";

    public MedOcrPrefillDto recognize(byte[] imageBytes, String mimeType) {
        return toPrefill(ocrRunner.recognizeJson(
                "ocr-med",
                structureUserPrompt(),
                imageBytes,
                mimeType,
                SYSTEM_PROMPT,
                FAILURE));
    }

    /** 驾驶舱入库：原文已抽出且配额已占用，只做结构化。 */
    public MedOcrPrefillDto recognizePrepared(byte[] imageBytes, String mimeType, String documentText) {
        return toPrefill(ocrRunner.structureDocument(
                "ocr-med", structureUserPrompt(), imageBytes, mimeType, documentText, SYSTEM_PROMPT, FAILURE));
    }

    private MedOcrPrefillDto toPrefill(JsonNode root) {
        List<String> warnings = new ArrayList<>();
        if (root.path("warnings").isArray()) {
            root.path("warnings").forEach(n -> {
                if (n.isTextual() && StringUtils.hasText(n.asText())) {
                    warnings.add(n.asText().trim());
                }
            });
        }
        List<MedOcrItemDto> items = new ArrayList<>();
        JsonNode arr = root.path("items");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                String drugName = textOrNull(node, "drugName");
                if (!StringUtils.hasText(drugName)) {
                    drugName = textOrNull(node, "name");
                }
                if (!StringUtils.hasText(drugName)) {
                    continue;
                }
                String usage = normalizeUsageMethod(textOrNull(node, "usageMethod"));
                items.add(new MedOcrItemDto(
                        drugName.trim(),
                        usage,
                        normalizeFrequency(textOrNull(node, "frequency"), warnings, drugName.trim()),
                        textOrNull(node, "doseAmount"),
                        normalizeDoseUnit(textOrNull(node, "doseUnit"), warnings, drugName.trim()),
                        textOrNull(node, "timingNote"),
                        intOrNull(node.get("courseDays")),
                        parseDate(textOrNull(node, "startDate"), warnings, drugName.trim())));
            }
        }
        if (items.isEmpty()) {
            warnings.add("未识别到有效药品条目");
        }
        return new MedOcrPrefillDto(items, warnings);
    }

    private static String structureUserPrompt() {
        return """
                请从处方/用药清单内容中抽取结构化信息，输出严格 JSON（不要 markdown 代码块）：
                {
                  "items": [
                    {
                      "drugName": "药品名称",
                      "usageMethod": "ORAL 或 INJECTION 或 INHALATION 或 null",
                      "frequency": "仅允许 QD/BID/TID/QID/QN/QOD/PRN，无法对应则 null",
                      "doseAmount": "单次剂量或 null",
                      "doseUnit": "仅允许 G/MG/ML/TABLET/CAPSULE/BAG/BOTTLE/SPRAY，无法对应则 null",
                      "timingNote": "服药时间说明或 null",
                      "courseDays": 疗程天数整数或 null,
                      "startDate": "开始日期 YYYY-MM-DD 或 null"
                    }
                  ],
                  "warnings": []
                }
                frequency/doseUnit/usageMethod 必须落在上述枚举；原文如「一日三次」「粒」请映射到 TID/CAPSULE，映射不了就填 null，不要输出自由文本。
                仅抽取单据上的药品与用法，不要诊断或调整剂量。
                """;
    }

    static String normalizeUsageMethod(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String t = raw.trim();
        String upper = t.toUpperCase(Locale.ROOT);
        if ("ORAL".equals(upper) || "INJECTION".equals(upper) || "INHALATION".equals(upper)) {
            return upper;
        }
        if (t.contains("口服") || t.contains("吞服") || t.contains("含服")) {
            return "ORAL";
        }
        if (t.contains("注射") || t.contains("肌注") || t.contains("静滴") || t.contains("静脉")) {
            return "INJECTION";
        }
        if (t.contains("吸入") || t.contains("雾化")) {
            return "INHALATION";
        }
        return null;
    }

    /**
     * 映射到 medicationFrequency 字典码；对不上系统选项则返回 null（不落自由文本）。
     */
    static String normalizeFrequency(String raw, List<String> warnings, String drugLabel) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String original = raw.trim();
        String s = original
                .toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace("　", "")
                .replace("／", "/")
                .replace("次/日", "次每日")
                .replace("次/天", "次每日");

        if (List.of("QD", "BID", "TID", "QID", "QN", "QOD", "PRN", "OTHER").contains(s)) {
            return s;
        }
        if (s.contains("PRN") || s.contains("必要时") || s.contains("需要时") || s.contains("按需") || s.contains("疼痛时")) {
            return "PRN";
        }
        if (s.contains("QOD") || s.contains("隔日") || s.contains("隔天")) {
            return "QOD";
        }
        if (s.contains("QN") || s.contains("每晚") || s.contains("睡前1") || s.contains("睡前一")) {
            return "QN";
        }
        if (s.contains("QID") || s.contains("每日4") || s.contains("每天4") || s.contains("一日4") || s.contains("一天4")
                || s.contains("4次") || s.contains("四次")) {
            return "QID";
        }
        if (s.contains("TID") || s.contains("每日3") || s.contains("每天3") || s.contains("一日3") || s.contains("一天3")
                || s.contains("3次") || s.contains("三次")) {
            return "TID";
        }
        if (s.contains("BID") || s.contains("每日2") || s.contains("每天2") || s.contains("一日2") || s.contains("一天2")
                || s.contains("2次") || s.contains("两次") || s.contains("二回")) {
            return "BID";
        }
        if (s.contains("QD") || s.contains("每日1") || s.contains("每天1") || s.contains("一日1") || s.contains("一天1")
                || s.contains("每日一") || s.contains("每天一") || s.contains("一日一") || s.contains("一天一")
                || s.contains("1次") || s.contains("一次")) {
            return "QD";
        }
        if (warnings != null) {
            warnings.add("「" + drugLabel + "」频次「" + original + "」无法对应系统选项，请手工选择");
        }
        return null;
    }

    /** 映射到 doseUnit 字典码；对不上则返回 null。 */
    static String normalizeDoseUnit(String raw, List<String> warnings, String drugLabel) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String original = raw.trim();
        String s = original.toUpperCase(Locale.ROOT).replace(" ", "");
        if (List.of("G", "MG", "ML", "TABLET", "CAPSULE", "BAG", "BOTTLE", "SPRAY", "OTHER").contains(s)) {
            return s;
        }
        if ("克".equals(original) || "g".equalsIgnoreCase(original)) {
            return "G";
        }
        if ("毫克".equals(original) || "mg".equalsIgnoreCase(original)) {
            return "MG";
        }
        if ("毫升".equals(original) || "ml".equalsIgnoreCase(original)) {
            return "ML";
        }
        if (original.contains("片") || "TABLET".equals(s)) {
            return "TABLET";
        }
        if (original.contains("粒") || original.contains("胶囊") || "CAPSULE".equals(s)) {
            return "CAPSULE";
        }
        if (original.contains("袋")) {
            return "BAG";
        }
        if (original.contains("支") || original.contains("瓶")) {
            return "BOTTLE";
        }
        if (original.contains("喷")) {
            return "SPRAY";
        }
        if (warnings != null) {
            warnings.add("「" + drugLabel + "」剂量单位「" + original + "」无法对应系统选项，请手工选择");
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

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        try {
            String text = node.asText().trim().replaceAll("[^0-9]", "");
            if (!StringUtils.hasText(text)) {
                return null;
            }
            return Integer.parseInt(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw, List<String> warnings, String drugLabel) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            warnings.add("「" + drugLabel + "」开始日期不清晰");
            return null;
        }
    }
}
