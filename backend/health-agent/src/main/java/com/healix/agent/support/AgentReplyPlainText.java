package com.healix.agent.support;

import org.springframework.util.StringUtils;

/**
 * 将 LLM 偶发输出的 Markdown / 粘连分段压成可读纯文本。
 */
public final class AgentReplyPlainText {

    private AgentReplyPlainText() {}

    public static String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');

        // 粘连标题：xxx## 二、 → xxx\n\n## 二、
        s = s.replaceAll("([^\\n])(#{1,6}\\s*)", "$1\n\n$2");
        // 去掉标题标记
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");
        // 粗体 / 斜体
        s = s.replaceAll("\\*\\*([^*\\n]+)\\*\\*", "$1");
        s = s.replaceAll("__([^_\\n]+)__", "$1");
        s = s.replaceAll("(?<!\\w)\\*([^*\\n]+)\\*(?!\\w)", "$1");
        // 勾选列表 / 无序列表（含「-、」这种变体）
        s = s.replaceAll("(?m)^\\s*[-*]\\s+\\[\\s*[xX ]?]\\s*", "· ");
        s = s.replaceAll("(?m)^\\s*[-*]、\\s*", "");
        s = s.replaceAll("(?m)^\\s*[-*]\\s+", "· ");
        // 分割线
        s = s.replaceAll("(?m)^\\s*-{3,}\\s*$", "");
        // 行内代码 / 代码围栏
        s = s.replaceAll("```[a-zA-Z]*\\n?", "");
        s = s.replace("```", "");
        s = s.replace("`", "");
        // Markdown 表格：拆成「列：值」短行，去掉对齐行
        s = flattenTables(s);
        // 中文分节与条目换行
        s = breakChineseSections(s);
        // 英文病种 code → 中文（防止模型照抄上下文）
        s = localizeDiseaseCodes(s);
        // 多余空行
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    /** 把回复里残留的英文病种 code 换成中文。 */
    static String localizeDiseaseCodes(String input) {
        String s = input;
        // 长短语优先，避免部分替换
        String[][] pairs = {
            {"type2_diabetes", "糖尿病"},
            {"type1_diabetes", "1型糖尿病"},
            {"coronary_heart_disease", "冠心病"},
            {"chronic_kidney_disease", "慢性肾病"},
            {"chronic_hepatitis", "慢性肝炎"},
            {"diabetes", "糖尿病"},
            {"hypertension", "高血压"},
            {"obesity", "肥胖症"},
            {"hyperlipidemia", "高脂血症"},
            {"osteoporosis", "骨质疏松"},
            {"hepatitis", "肝炎"},
            {"gout", "痛风"},
            {"stroke", "脑卒中"},
            {"copd", "慢阻肺"},
            {"ckd", "慢性肾病"},
            {"cad", "冠心病"},
            {"t2dm", "糖尿病"},
            {"t1dm", "1型糖尿病"},
            {"htn", "高血压"},
            {"ascvd", "动脉粥样硬化性心血管疾病"},
        };
        for (String[] pair : pairs) {
            s = s.replaceAll("(?i)\\b" + pair[0] + "\\b", pair[1]);
        }
        return s;
    }

    /**
     * 把「一、标题1.条目」「。2.下一条」这类粘连拆成多行。
     */
    static String breakChineseSections(String input) {
        String s = input;
        // 「一、当前风险判断1.血压」→ 标题后换行（顿号/点号均支持）
        // 注意：不要把正文「三、李四的超期…」强行拆成新行当标题
        s = s.replaceAll(
                "([一二三四五六七八九十百]+[、.．][^\\n\\d]{1,40}?)(\\d+[\\.．、）)])", "$1\n$2");
        // 「。2.下一条」/「；3）下一条」句末后的下一条换行
        s = s.replaceAll("([。！？；;])(\\d+[\\.．、）)])", "$1\n$2");
        // 「·条目」粘在句末后
        s = s.replaceAll("([。！？；;])(·\\s*)", "$1\n$2");
        return s;
    }

    private static String flattenTables(String input) {
        String[] lines = input.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (isTableSeparator(trimmed)) {
                continue;
            }
            if (trimmed.contains("|") && countPipes(trimmed) >= 2) {
                String[] cells = trimmed.split("\\|");
                StringBuilder row = new StringBuilder();
                for (String cell : cells) {
                    String c = cell.trim();
                    if (!StringUtils.hasText(c)) {
                        continue;
                    }
                    if (!row.isEmpty()) {
                        row.append("｜");
                    }
                    row.append(c);
                }
                if (!row.isEmpty()) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(row);
                }
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    private static boolean isTableSeparator(String trimmed) {
        if (!trimmed.contains("|") && !trimmed.contains("-")) {
            return false;
        }
        String compact = trimmed.replace("|", "").replace(":", "").replace("-", "").replace(" ", "");
        return compact.isEmpty() && trimmed.contains("-");
    }

    private static int countPipes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '|') {
                n++;
            }
        }
        return n;
    }
}
