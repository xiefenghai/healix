package com.healix.core.observation.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 检查类型与关键测量字段目录，与前端 {@code exam-panels.ts} 保持一致。
 *
 * <p>OCR 需要在服务端知道「有哪些检查类型」「每类允许哪些结构化字段及取值」，
 * 否则模型会自由发挥出无法入库的字段。同时提供中文别名 → code 的映射，
 * 因为报告单上写的是「颈动脉彩超」而不是 {@code CAROTID_US}。
 */
public final class ExamTypeCatalog {

    /** 关键测量字段类型 */
    public enum FieldType {
        NUMBER,
        BOOL,
        SELECT
    }

    /**
     * @param options SELECT 的合法取值；其余类型为空
     * @param unit 数值单位，仅用于提示模型
     */
    public record FindingField(String key, String label, FieldType type, List<String> options, String unit) {

        static FindingField number(String key, String label, String unit) {
            return new FindingField(key, label, FieldType.NUMBER, List.of(), unit);
        }

        static FindingField bool(String key, String label) {
            return new FindingField(key, label, FieldType.BOOL, List.of(), null);
        }

        static FindingField select(String key, String label, String... options) {
            return new FindingField(key, label, FieldType.SELECT, List.of(options), null);
        }
    }

    private record ExamTypeSpec(String code, String label, List<String> aliases, List<FindingField> fields) {}

    private static final List<ExamTypeSpec> SPECS = List.of(
            new ExamTypeSpec(
                    "ECG",
                    "心电图",
                    List.of("心电图", "常规心电图", "十二导联", "ECG", "EKG"),
                    List.of(
                            FindingField.select("rhythm", "心律", "SINUS", "AF", "OTHER"),
                            FindingField.select("hasIschemiaHint", "缺血提示", "YES", "NO", "NA"))),
            new ExamTypeSpec(
                    "UCG",
                    "心脏彩超",
                    List.of("心脏彩超", "心脏超声", "超声心动图", "心动超声", "UCG", "ECHO"),
                    List.of(FindingField.number("efPercent", "EF", "%"))),
            new ExamTypeSpec(
                    "CAROTID_US",
                    "颈动脉彩超",
                    List.of("颈动脉彩超", "颈动脉超声", "颈部血管超声", "颈动脉"),
                    List.of(
                            FindingField.number("cimtMm", "CIMT", "mm"),
                            FindingField.bool("hasPlaque", "斑块"))),
            new ExamTypeSpec(
                    "ABDOMINAL_US",
                    "腹部超声",
                    List.of("腹部超声", "腹部彩超", "肝胆脾", "上腹部超声", "肝胆胰脾"),
                    List.of(
                            FindingField.select(
                                    "fattyLiver", "脂肪肝", "NORMAL", "MILD", "MODERATE", "SEVERE", "UNGRADED"),
                            FindingField.bool("kidneyNormal", "肾脏形态正常"))),
            new ExamTypeSpec(
                    "THYROID_US",
                    "甲状腺超声",
                    List.of("甲状腺超声", "甲状腺彩超", "甲状腺"),
                    List.of(
                            FindingField.bool("nodulePresent", "结节"),
                            FindingField.select("tiRads", "TI-RADS", "1", "2", "3", "4", "5", "UNGRADED"))),
            new ExamTypeSpec(
                    "CHEST_IMAGING",
                    "胸部影像",
                    List.of("胸部影像", "胸片", "胸部X线", "胸部CT", "肺CT", "DR胸部"),
                    List.of(FindingField.bool("nodulePresent", "结节"))),
            new ExamTypeSpec(
                    "ABDOMINAL_CT",
                    "腹部CT",
                    List.of("腹部CT", "上腹部CT", "全腹CT", "腹部平扫"),
                    List.of(FindingField.bool("lesionPresent", "占位"))),
            new ExamTypeSpec(
                    "PFT",
                    "肺功能",
                    List.of("肺功能", "肺通气功能", "肺功能测定"),
                    List.of(
                            FindingField.number("fev1Fvc", "FEV1/FVC", null),
                            FindingField.number("fev1PredPercent", "FEV1占预计值", "%"))),
            new ExamTypeSpec(
                    "BMD",
                    "骨密度",
                    List.of("骨密度", "双能X线", "DXA", "骨密度测定"),
                    List.of(
                            FindingField.number("tScore", "T值", null),
                            FindingField.number("zScore", "Z值", null))),
            new ExamTypeSpec(
                    "FUNDUS",
                    "眼底检查",
                    List.of("眼底", "眼底检查", "眼底照相", "眼底彩照"),
                    List.of(FindingField.select(
                            "drGrade", "DR分级", "NONE", "MILD", "MOD", "SEVERE", "PDR", "UNGRADED"))));

    private static final Map<String, ExamTypeSpec> BY_CODE = new LinkedHashMap<>();

    static {
        for (ExamTypeSpec spec : SPECS) {
            BY_CODE.put(spec.code(), spec);
        }
    }

    private ExamTypeCatalog() {}

    public static Set<String> codes() {
        return BY_CODE.keySet();
    }

    public static boolean isKnownCode(String code) {
        return StringUtils.hasText(code) && BY_CODE.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }

    public static String label(String code) {
        ExamTypeSpec spec = code == null ? null : BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
        return spec == null ? code : spec.label();
    }

    public static List<FindingField> fields(String code) {
        ExamTypeSpec spec = code == null ? null : BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
        return spec == null ? List.of() : spec.fields();
    }

    /**
     * 报告单上的检查名称 → code。
     *
     * <p>先按 code 直命中，再按别名做包含匹配（长别名优先，避免「胸部CT」被「胸部影像」的短别名抢走）。
     */
    public static Optional<String> resolveCode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (BY_CODE.containsKey(normalized)) {
            return Optional.of(normalized);
        }
        String best = null;
        int bestLen = 0;
        for (ExamTypeSpec spec : SPECS) {
            for (String alias : spec.aliases()) {
                String a = alias.toUpperCase(Locale.ROOT).replace(" ", "");
                if (normalized.contains(a) && a.length() > bestLen) {
                    best = spec.code();
                    bestLen = a.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** 供 LLM prompt 使用的字段清单描述。 */
    public static String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (ExamTypeSpec spec : SPECS) {
            sb.append("- ").append(spec.code()).append("（").append(spec.label()).append("）");
            if (spec.fields().isEmpty()) {
                sb.append("：无结构化字段\n");
                continue;
            }
            sb.append("：");
            for (int i = 0; i < spec.fields().size(); i++) {
                FindingField f = spec.fields().get(i);
                if (i > 0) {
                    sb.append("，");
                }
                sb.append(f.key()).append("(").append(f.label());
                sb.append(switch (f.type()) {
                    case NUMBER -> StringUtils.hasText(f.unit()) ? "，数值 " + f.unit() : "，数值";
                    case BOOL -> "，true/false";
                    case SELECT -> "，取值 " + String.join("/", f.options());
                });
                sb.append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
