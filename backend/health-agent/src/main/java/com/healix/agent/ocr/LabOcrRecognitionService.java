package com.healix.agent.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.core.observation.dto.LabOcrPrefillDto;
import com.healix.core.observation.dto.LabOcrPrefillDto.LabOcrIgnoredItemDto;
import com.healix.core.observation.dto.LabOcrPrefillDto.LabOcrItemDto;
import com.healix.core.observation.support.LabItemCodeMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class LabOcrRecognitionService {

    private final LabItemCodeMapper labItemCodeMapper;
    private final OcrRunner ocrRunner;

    public LabOcrPrefillDto recognize(byte[] imageBytes, String mimeType) {
        return toPrefill(ocrRunner.recognizeJson(
                "ocr-lab",
                structureUserPrompt(),
                imageBytes,
                mimeType,
                "你是医疗检验单 OCR 助手，仅输出 JSON，不做诊断。",
                "检验单识别失败，请检查图片清晰度或稍后重试"));
    }

    private LabOcrPrefillDto toPrefill(JsonNode root) {
        List<String> warnings = new ArrayList<>();
        if (root.path("warnings").isArray()) {
            root.path("warnings").forEach(n -> {
                if (n.isTextual() && StringUtils.hasText(n.asText())) {
                    warnings.add(n.asText());
                }
            });
        }
        List<LabOcrItemDto> mappedItems = new ArrayList<>();
        List<LabOcrIgnoredItemDto> ignored = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        JsonNode items = root.path("items");
        if (items.isArray()) {
            for (JsonNode node : items) {
                String rawName = textOrNull(node, "itemName");
                if (!StringUtils.hasText(rawName)) {
                    rawName = textOrNull(node, "name");
                }
                String hintedCode = textOrNull(node, "itemCode");
                Optional<String> codeOpt = StringUtils.hasText(hintedCode) && labItemCodeMapper.isKnownCode(hintedCode)
                        ? Optional.of(hintedCode)
                        : labItemCodeMapper.map(rawName);
                if (codeOpt.isEmpty()) {
                    if (StringUtils.hasText(rawName)) {
                        ignored.add(new LabOcrIgnoredItemDto(rawName.trim(), "NOT_IN_CATALOG"));
                    }
                    continue;
                }
                String code = codeOpt.get();
                if (!seenCodes.add(code)) {
                    continue;
                }
                BigDecimal valueNum = decimalOrNull(node.get("valueNum"));
                String valueText = textOrNull(node, "valueText");
                if (valueNum == null && !StringUtils.hasText(valueText)) {
                    continue;
                }
                mappedItems.add(new LabOcrItemDto(
                        code,
                        labItemCodeMapper.resolveName(code),
                        valueNum,
                        valueText,
                        textOrNull(node, "unit"),
                        decimalOrNull(node.get("refLow")),
                        decimalOrNull(node.get("refHigh")),
                        normalizeFlag(textOrNull(node, "abnormalFlag"))));
            }
        }
        if (!ignored.isEmpty()) {
            warnings.add("已忽略 " + ignored.size() + " 项未收录项目");
        }
        String specimen = normalizeSpecimen(textOrNull(root, "specimenType"));
        return new LabOcrPrefillDto(
                specimen,
                parseDateTime(textOrNull(root, "sampledAt"), warnings, "采样时间"),
                parseDateTime(textOrNull(root, "reportedAt"), warnings, "报告时间"),
                textOrNull(root, "note"),
                mappedItems,
                ignored,
                warnings);
    }

    private static String structureUserPrompt() {
        return """
                请从检验/化验单内容中抽取结构化信息，输出严格 JSON（不要 markdown 代码块）：
                {
                  "specimenType": "BLOOD 或 URINE 或 null",
                  "sampledAt": "采样时间 ISO-8601 或 null",
                  "reportedAt": "报告时间 ISO-8601 或 null",
                  "note": "备注或 null",
                  "items": [
                    {
                      "itemName": "项目中文名",
                      "valueNum": 数值或null,
                      "valueText": "定性结果或null",
                      "unit": "单位",
                      "refLow": 参考下限或null,
                      "refHigh": 参考上限或null,
                      "abnormalFlag": "H/L/N 或 null"
                    }
                  ],
                  "warnings": []
                }
                仅抽取常规检验项目，不要诊断结论。
                """;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String text = v.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            String text = node.asText().trim();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            return new BigDecimal(text.replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeFlag(String flag) {
        if (!StringUtils.hasText(flag)) {
            return null;
        }
        String f = flag.trim().toUpperCase(Locale.ROOT);
        if ("H".equals(f) || "L".equals(f) || "N".equals(f)) {
            return f;
        }
        return null;
    }

    private static String normalizeSpecimen(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.contains("尿")) {
            return "URINE";
        }
        if (s.contains("血") || "BLOOD".equals(s)) {
            return "BLOOD";
        }
        return null;
    }

    private static LocalDateTime parseDateTime(String raw, List<String> warnings, String label) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            warnings.add(label + "不清晰");
            return null;
        }
    }
}
