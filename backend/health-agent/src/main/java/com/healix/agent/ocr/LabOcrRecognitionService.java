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
                SYSTEM_PROMPT,
                FAILURE));
    }

    /** 驾驶舱入库：原文已抽出且配额已占用，只做结构化。 */
    public LabOcrPrefillDto recognizePrepared(byte[] imageBytes, String mimeType, String documentText) {
        return toPrefill(ocrRunner.structureDocument(
                "ocr-lab", structureUserPrompt(), imageBytes, mimeType, documentText, SYSTEM_PROMPT, FAILURE));
    }

    private static final String SYSTEM_PROMPT = "你是医疗检验单 OCR 助手，仅输出 JSON，不做诊断。";
    private static final String FAILURE = "检验单识别失败，请检查图片清晰度或稍后重试";

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
                    ignored.add(new LabOcrIgnoredItemDto(
                            StringUtils.hasText(rawName) ? rawName.trim() : code, "DUPLICATE_CODE"));
                    continue;
                }
                BigDecimal valueNum = decimalOrNull(node.get("valueNum"));
                String valueText = textOrNull(node, "valueText");
                if (valueNum == null && !StringUtils.hasText(valueText)) {
                    // 尝试从 value 字段取数（部分模型不拆 valueNum）
                    valueNum = decimalOrNull(node.get("value"));
                    if (valueNum == null && !StringUtils.hasText(valueText)) {
                        continue;
                    }
                }
                RefBound ref = parseRefBound(node);
                String flag = normalizeFlag(textOrNull(node, "abnormalFlag"));
                if (flag == null) {
                    flag = inferFlag(
                            textOrNull(node, "abnormalFlag"),
                            textOrNull(node, "flag"),
                            valueText,
                            textOrNull(node, "value"),
                            rawName);
                }
                mappedItems.add(new LabOcrItemDto(
                        code,
                        labItemCodeMapper.resolveName(code),
                        valueNum,
                        valueText,
                        textOrNull(node, "unit"),
                        ref.low(),
                        ref.high(),
                        flag));
            }
        }
        // 忽略项数量由前端根据 ignoredItems 汇总展示，避免与「未映射」提示重复
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
                要求：
                1) 血常规须尽量抽全：白细胞/红细胞/血红蛋白/血小板、分类绝对值与百分比、MCV/MCH/MCHC/RDW、MPV/PDW/PCT 等；
                2) 报告单上的 ↑↓ 或 H/L 写入 abnormalFlag（H/L/N）；
                3) 单侧参考如 <0.06 只填 refHigh，>40 只填 refLow；
                4) 仅抽取检验项目，不要诊断结论。
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
        if ("HIGH".equals(f) || f.contains("↑") || f.contains("⬆") || f.contains("偏高")
                || f.contains("为 H") || f.contains("为H") || f.endsWith(" H")) {
            return "H";
        }
        if ("LOW".equals(f) || f.contains("↓") || f.contains("⬇") || f.contains("偏低")
                || f.contains("为 L") || f.contains("为L") || f.endsWith(" L")) {
            return "L";
        }
        return null;
    }

    /** 从 ↑↓ / 偏高偏低 等文本推断异常标记 */
    private static String inferFlag(String... texts) {
        if (texts == null) {
            return null;
        }
        for (String raw : texts) {
            String hit = normalizeFlag(raw);
            if (hit != null && !"N".equals(hit)) {
                return hit;
            }
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String t = raw.trim();
            // 数值后紧跟箭头：12.5↑ / 3.1↓
            if (t.matches(".*\\d\\s*[↑⬆].*") || t.matches("(?i).*\\bH\\b.*")) {
                return "H";
            }
            if (t.matches(".*\\d\\s*[↓⬇].*") || t.matches("(?i).*\\bL\\b.*")) {
                return "L";
            }
        }
        return null;
    }

    private record RefBound(BigDecimal low, BigDecimal high) {}

    /**
     * 解析参考范围：支持独立 refLow/refHigh，或 refRange 文本如 {@code 3.5-9.5}、{@code <0.06}、{@code >40}。
     */
    private static RefBound parseRefBound(JsonNode node) {
        BigDecimal low = decimalOrNull(node.get("refLow"));
        BigDecimal high = decimalOrNull(node.get("refHigh"));
        if (low != null || high != null) {
            return new RefBound(low, high);
        }
        String range = textOrNull(node, "refRange");
        if (!StringUtils.hasText(range)) {
            range = textOrNull(node, "reference");
        }
        if (!StringUtils.hasText(range)) {
            return new RefBound(null, null);
        }
        String t = range.trim().replace("～", "-").replace("~", "-").replace("—", "-");
        if (t.startsWith("<") || t.startsWith("≤") || t.startsWith("<=")) {
            return new RefBound(null, decimalFromLoose(t.replaceAll("^[≤<]=?", "")));
        }
        if (t.startsWith(">") || t.startsWith("≥") || t.startsWith(">=")) {
            return new RefBound(decimalFromLoose(t.replaceAll("^[≥>]=?", "")), null);
        }
        String[] parts = t.split("-");
        if (parts.length == 2) {
            return new RefBound(decimalFromLoose(parts[0]), decimalFromLoose(parts[1]));
        }
        return new RefBound(null, null);
    }

    private static BigDecimal decimalFromLoose(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return null;
        }
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
            warnings.add("未识别出" + label);
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
