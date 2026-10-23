package com.healix.agent.extract;

import java.util.List;
import java.util.Map;

/** 第一期允许写入的指标和档案字段。编码与现有字典一致。 */
public final class ChatExtractCatalog {

    record VitalField(String label, String unit) {}

    record ArchiveField(
            String code,
            String label,
            boolean text,
            Double min,
            Double max,
            List<ChatExtractOption> options) {}

    static final Map<String, VitalField> VITALS = Map.of(
            "BLOOD_GLUCOSE", new VitalField("血糖", "mmol/L"),
            "HEART_RATE", new VitalField("心率", "次/分"),
            "WEIGHT", new VitalField("体重", "kg"),
            "WAIST", new VitalField("腰围", "cm"));

    static final Map<String, ArchiveField> ARCHIVE = Map.ofEntries(
            Map.entry("diet.appetite", options("diet.appetite", "食欲", "GOOD", "好", "NORMAL", "正常", "FAIR", "一般", "POOR", "较差")),
            Map.entry("diet.habit", options("diet.habit", "饮食规律", "GOOD", "好", "FAIR", "一般", "POOR", "差")),
            Map.entry(
                    "diet.type",
                    options(
                            "diet.type",
                            "饮食类型",
                            "NORMAL",
                            "普通饮食",
                            "VEGETARIAN",
                            "素食",
                            "LOW_SALT",
                            "低盐",
                            "LOW_SUGAR",
                            "低糖",
                            "LOW_SALT_SUGAR",
                            "低盐低糖")),
            Map.entry("diet.preference", text("diet.preference", "饮食偏好")),
            Map.entry(
                    "exercise.frequency",
                    options(
                            "exercise.frequency",
                            "运动频率",
                            "NONE",
                            "基本不运动",
                            "WEEKLY_1",
                            "每周1-2次",
                            "WEEKLY_3",
                            "每周≥3次",
                            "DAILY",
                            "每天")),
            Map.entry("exercise.intensity", options("exercise.intensity", "运动强度", "LIGHT", "轻度", "MODERATE", "中度", "VIGOROUS", "重度")),
            Map.entry("exercise.durationMin", number("exercise.durationMin", "每次运动时长", 1, 300)),
            Map.entry("exercise.type", text("exercise.type", "运动方式")),
            Map.entry("sleep.quality", options("sleep.quality", "睡眠质量", "GOOD", "良好", "FAIR", "一般", "POOR", "较差")),
            Map.entry("sleep.hours", number("sleep.hours", "平时睡眠小时", 0.5, 24)),
            Map.entry(
                    "sleep.disorder",
                    options("sleep.disorder", "睡眠障碍", "NONE", "无", "INSOMNIA", "失眠", "SNORING", "打鼾/睡眠呼吸问题")),
            Map.entry(
                    "lifestyle.smoking.status",
                    options(
                            "lifestyle.smoking.status",
                            "吸烟状态",
                            "NEVER",
                            "从不",
                            "CURRENT",
                            "当前吸烟",
                            "OCCASIONAL",
                            "偶尔",
                            "FORMER",
                            "已戒烟")),
            Map.entry("lifestyle.smoking.cigarettesPerDay", number("lifestyle.smoking.cigarettesPerDay", "每天支数", 0, 100)),
            Map.entry("lifestyle.smoking.years", number("lifestyle.smoking.years", "烟龄", 0, 80)),
            Map.entry("lifestyle.smoking.quitYear", number("lifestyle.smoking.quitYear", "戒烟年份", 1900, 2100)),
            Map.entry(
                    "lifestyle.drinking.status",
                    options(
                            "lifestyle.drinking.status",
                            "饮酒状态",
                            "NEVER",
                            "从不",
                            "CURRENT",
                            "当前饮酒",
                            "OCCASIONAL",
                            "偶尔",
                            "FORMER",
                            "已戒酒")),
            Map.entry(
                    "lifestyle.drinking.frequency",
                    options(
                            "lifestyle.drinking.frequency",
                            "饮酒频率",
                            "DAILY",
                            "每天",
                            "WEEKLY_3",
                            "每周≥3次",
                            "WEEKLY_1_2",
                            "每周1-2次",
                            "MONTHLY",
                            "每月数次")),
            Map.entry("lifestyle.drinking.type", text("lifestyle.drinking.type", "酒的种类")),
            Map.entry("lifestyle.drinking.amountPerDay", text("lifestyle.drinking.amountPerDay", "饮酒日均量")));

    private ChatExtractCatalog() {}

    static String alias(String code, String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (code.endsWith("smoking.status")) {
            if ("已戒".equals(t) || "戒了".equals(t) || "QUIT".equalsIgnoreCase(t) || "已戒烟".equals(t)) {
                return "FORMER";
            }
            if ("不抽烟".equals(t) || "不吸烟".equals(t) || "从不抽烟".equals(t)) {
                return "NEVER";
            }
            if ("偶尔抽".equals(t) || "偶尔吸烟".equals(t)) {
                return "OCCASIONAL";
            }
            if ("还在抽".equals(t) || "每天抽".equals(t)) {
                return "CURRENT";
            }
        }
        if (code.endsWith("drinking.status")) {
            if ("已戒".equals(t) || "QUIT".equalsIgnoreCase(t) || "已戒酒".equals(t) || "戒酒".equals(t)) {
                return "FORMER";
            }
            if ("不喝酒".equals(t) || "不饮酒".equals(t)) {
                return "NEVER";
            }
            if ("偶尔喝".equals(t) || "偶尔饮酒".equals(t)) {
                return "OCCASIONAL";
            }
        }
        if ("diet.habit".equals(code)) {
            if ("规律".equals(t) || "饮食规律".equals(t) || "吃饭规律".equals(t)) {
                return "GOOD";
            }
            if ("还算规律".equals(t) || "比较规律".equals(t)) {
                return "FAIR";
            }
            if ("不规律".equals(t)) {
                return "POOR";
            }
        }
        if ("diet.appetite".equals(code)) {
            if ("食欲正常".equals(t)) {
                return "NORMAL";
            }
            if ("食欲好".equals(t) || "食欲不错".equals(t)) {
                return "GOOD";
            }
            if ("食欲一般".equals(t)) {
                return "FAIR";
            }
            if ("食欲差".equals(t) || "食欲不好".equals(t) || "食欲较差".equals(t)) {
                return "POOR";
            }
        }
        if ("exercise.intensity".equals(code)) {
            if ("中等".equals(t) || "中等强度".equals(t)) {
                return "MODERATE";
            }
            if ("剧烈".equals(t) || "高强度".equals(t)) {
                return "VIGOROUS";
            }
            if ("轻度".equals(t)) {
                return "LIGHT";
            }
        }
        if ("sleep.disorder".equals(code) && ("打鼾".equals(t) || "打呼噜".equals(t))) {
            return "SNORING";
        }
        return null;
    }

    static String display(String code, String value) {
        ArchiveField field = ARCHIVE.get(code);
        if (field == null || value == null) {
            return value;
        }
        for (ChatExtractOption option : field.options()) {
            if (option.getValue().equals(value)) {
                return option.getLabel();
            }
        }
        return value;
    }

    private static ArchiveField options(String code, String label, String... pairs) {
        List<ChatExtractOption> options = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            options.add(new ChatExtractOption(pairs[i], pairs[i + 1]));
        }
        return new ArchiveField(code, label, false, null, null, List.copyOf(options));
    }

    private static ArchiveField text(String code, String label) {
        return new ArchiveField(code, label, true, null, null, List.of());
    }

    private static ArchiveField number(String code, String label, double min, double max) {
        return new ArchiveField(code, label, false, min, max, List.of());
    }
}
