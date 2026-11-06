package com.healix.agent.extract;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 把模型对问卷题目的输出收成核对卡。
 * 只校验字段、选项和原句是否出现在原文里，不按关键词补题。
 */
public final class ChatExtractIntakeNormalizer {

    static final int MAX_ROWS = 12;
    private static final Set<String> TYPES = Set.of("ENUM", "MULTI", "NUMBER", "TEXT", "DATE", "TIME", "YEAR");
    private static final int MAX_TEXT = 200;

    private ChatExtractIntakeNormalizer() {}

    public static ChatExtractCard normalize(String sourceText, JsonNode raw, List<IntakeExtractField> fields) {
        ChatExtractCard card = new ChatExtractCard();
        card.setIntake(true);
        String source = sourceText == null ? "" : sourceText;
        Map<String, IntakeExtractField> byCode = index(fields);
        if (raw != null && raw.has("ignored") && raw.get("ignored").isArray()) {
            for (JsonNode ignored : raw.get("ignored")) {
                String quote = text(ignored, "quote");
                if (contained(source, quote)) {
                    card.getIgnored().add(new ChatExtractIgnored(clip(quote), text(ignored, "reason")));
                }
            }
        }
        JsonNode items = raw == null ? null : raw.get("items");
        if (items == null || !items.isArray()) {
            return card;
        }
        int index = 1;
        for (JsonNode item : items) {
            if (card.getItems().size() >= MAX_ROWS) {
                card.setOverflow(true);
                break;
            }
            String code = text(item, "field");
            if (!StringUtils.hasText(code)) {
                code = text(item, "code");
            }
            final String fieldCode = code;
            IntakeExtractField field = byCode.get(fieldCode);
            String quote = text(item, "quote");
            if (field == null || !contained(source, quote)) {
                continue;
            }
            String stored = store(field, item.get("value"));
            if (!StringUtils.hasText(stored)) {
                continue;
            }
            if (card.getItems().stream().anyMatch(row -> fieldCode.equals(row.getCode()))) {
                ChatExtractRow kept = card.getItems().stream()
                        .filter(row -> fieldCode.equals(row.getCode()))
                        .findFirst()
                        .orElse(null);
                if (kept != null && !stored.equals(kept.getArchiveValue()) && contained(source, quote)) {
                    card.getIgnored().add(new ChatExtractIgnored(
                            clip(quote.trim()),
                            field.label() + "还有另一种说法，核对时请看这一句"));
                }
                continue;
            }
            ChatExtractRow row = new ChatExtractRow();
            row.setId("i" + index++);
            row.setTarget("INTAKE");
            row.setCode(fieldCode);
            row.setLabel(field.label());
            row.setUnit(field.unit());
            row.setArchiveValue(stored);
            row.setQuote(clip(quote.trim()));
            row.setQuoteTruncated(quote.trim().length() > ChatExtractNormalizer.MAX_QUOTE);
            row.setStatus("READY");
            row.setIntakeType(field.type());
            row.setIntakeMin(field.min());
            row.setIntakeMax(field.max());
            row.setMulti("MULTI".equals(field.type()));
            row.setTextField("TEXT".equals(field.type()));
            if (field.options() != null) {
                row.setOptions(new ArrayList<>(field.options()));
            }
            card.getItems().add(row);
        }
        return card;
    }

    private static Map<String, IntakeExtractField> index(List<IntakeExtractField> fields) {
        Map<String, IntakeExtractField> byCode = new LinkedHashMap<>();
        if (fields == null) {
            return byCode;
        }
        for (IntakeExtractField field : fields) {
            if (field == null || !StringUtils.hasText(field.code()) || !TYPES.contains(field.type())) {
                continue;
            }
            if (("ENUM".equals(field.type()) || "MULTI".equals(field.type()))
                    && (field.options() == null || field.options().isEmpty())) {
                continue;
            }
            byCode.putIfAbsent(field.code().trim(), field);
        }
        return byCode;
    }

    private static String store(IntakeExtractField field, JsonNode value) {
        return switch (field.type()) {
            case "ENUM" -> optionCode(field, scalar(value));
            case "MULTI" -> multi(field, value);
            case "NUMBER", "YEAR" -> number(field, scalar(value));
            case "DATE" -> matches(scalar(value), "\\d{4}-\\d{2}-\\d{2}");
            case "TIME" -> matches(scalar(value), "\\d{2}:\\d{2}");
            case "TEXT" -> textValue(scalar(value));
            default -> null;
        };
    }

    private static String optionCode(IntakeExtractField field, String raw) {
        if (!StringUtils.hasText(raw) || field.options() == null) {
            return null;
        }
        String token = raw.trim();
        for (ChatExtractOption option : field.options()) {
            if (option == null || !StringUtils.hasText(option.getValue())) {
                continue;
            }
            if (option.getValue().equalsIgnoreCase(token)) {
                return option.getValue();
            }
            if (StringUtils.hasText(option.getLabel()) && option.getLabel().trim().equals(token)) {
                return option.getValue();
            }
        }
        return null;
    }

    private static String multi(IntakeExtractField field, JsonNode value) {
        List<String> codes = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode node : value) {
                addOption(codes, optionCode(field, scalar(node)));
            }
        } else {
            addOption(codes, optionCode(field, scalar(value)));
        }
        if (codes.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(codes.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        json.append(']');
        return json.toString();
    }

    private static void addOption(List<String> codes, String code) {
        if (StringUtils.hasText(code) && !codes.contains(code)) {
            codes.add(code);
        }
    }

    private static String number(IntakeExtractField field, String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        double value = number.doubleValue();
        if (field.min() != null && value < field.min()) {
            return null;
        }
        if (field.max() != null && value > field.max()) {
            return null;
        }
        return number.stripTrailingZeros().toPlainString();
    }

    private static String matches(String raw, String pattern) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String token = raw.trim();
        return token.matches(pattern) ? token : null;
    }

    private static String textValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String token = raw.trim();
        if (token.length() > MAX_TEXT) {
            token = token.substring(0, MAX_TEXT);
        }
        return token;
    }

    private static String scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return "";
    }

    private static String text(JsonNode node, String name) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return "";
        }
        return node.get(name).asText("").trim();
    }

    static boolean contained(String source, String quote) {
        if (!StringUtils.hasText(quote)) {
            return false;
        }
        return compact(source).contains(compact(quote));
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String clip(String quote) {
        if (quote.length() <= ChatExtractNormalizer.MAX_QUOTE) {
            return quote;
        }
        return quote.substring(0, ChatExtractNormalizer.MAX_QUOTE);
    }
}
