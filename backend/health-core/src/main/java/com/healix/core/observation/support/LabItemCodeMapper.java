package com.healix.core.observation.support;

import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.service.DictService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class LabItemCodeMapper {

    private static final Map<String, String> ALIASES = buildAliases();

    private final DictService dictService;

    public Optional<String> map(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return Optional.empty();
        }
        String normalized = normalize(rawName);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        Optional<String> aliasHit = matchAlias(normalized);
        if (aliasHit.isPresent()) {
            return aliasHit;
        }
        for (DictItemDto item : catalog()) {
            String code = item.getDictCode();
            if (code != null && code.equalsIgnoreCase(normalized)) {
                return Optional.of(code);
            }
            String desc = normalize(item.getDictCodeDesc());
            if (StringUtils.hasText(desc) && desc.equals(normalized)) {
                return Optional.of(code);
            }
        }
        // 模糊匹配必须按名称长度降序，避免「糖化血红蛋白」被「血红蛋白」先命中
        String bestCode = null;
        int bestLen = -1;
        for (DictItemDto item : catalog()) {
            String desc = normalize(item.getDictCodeDesc());
            if (!StringUtils.hasText(desc)) {
                continue;
            }
            if (!(normalized.contains(desc) || desc.contains(normalized))) {
                continue;
            }
            if (desc.length() > bestLen) {
                bestLen = desc.length();
                bestCode = item.getDictCode();
            }
        }
        return Optional.ofNullable(bestCode);
    }

    public boolean isKnownCode(String itemCode) {
        if (!StringUtils.hasText(itemCode)) {
            return false;
        }
        String code = itemCode.trim();
        if (catalog().stream().anyMatch(d -> code.equals(d.getDictCode()))) {
            return true;
        }
        // 字典种子尚未写入时，别名表内标准码仍视为可入库，避免 OCR 预填后保存被拒
        return ALIASES.containsValue(code);
    }

    public String resolveName(String itemCode) {
        if (!StringUtils.hasText(itemCode)) {
            return itemCode;
        }
        String code = itemCode.trim();
        Optional<String> fromDict = catalog().stream()
                .filter(d -> code.equals(d.getDictCode()))
                .map(DictItemDto::getDictCodeDesc)
                .filter(StringUtils::hasText)
                .findFirst();
        if (fromDict.isPresent()) {
            return fromDict.get();
        }
        // 回落：别名表中该码对应的最长中文名
        String best = null;
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (!code.equals(e.getValue())) {
                continue;
            }
            String name = e.getKey();
            if (!name.chars().anyMatch(ch -> ch >= 0x4e00 && ch <= 0x9fa5)) {
                continue;
            }
            if (best == null || name.length() > best.length()) {
                best = name;
            }
        }
        return best != null ? best : code;
    }

    private List<DictItemDto> catalog() {
        return dictService.listMerged(DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), "labItemCode");
    }

    /** 别名最长优先，避免「中性粒细胞百分比」误命中「中性粒细胞」。 */
    private static Optional<String> matchAlias(String normalized) {
        if (ALIASES.containsKey(normalized)) {
            return Optional.of(ALIASES.get(normalized));
        }
        List<String> keys = new ArrayList<>(ALIASES.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String key : keys) {
            if (key.length() < 2) {
                continue;
            }
            if (normalized.equals(key) || normalized.startsWith(key) || normalized.contains(key)) {
                // 百分比/比值类关键字时，禁止回落到绝对值别名
                boolean nameIsPct = isPercentName(normalized);
                boolean keyIsPct = isPercentName(key);
                if (nameIsPct != keyIsPct) {
                    continue;
                }
                return Optional.of(ALIASES.get(key));
            }
        }
        return Optional.empty();
    }

    private static boolean isPercentName(String normalized) {
        return normalized.contains("%")
                || normalized.contains("百分比")
                || normalized.contains("百分率")
                || normalized.contains("比值")
                || normalized.contains("比率")
                || normalized.endsWith("pct");
    }

    static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String s = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('μ', 'u')
                .replace("（", "(")
                .replace("）", ")")
                .replace("％", "%")
                .replaceAll("\\s+", "");
        // 去掉括号内单位/英文缩写：血红蛋白(g/L) → 血红蛋白
        s = s.replaceAll("\\([^)]*\\)", "");
        s = s.replaceAll("[^a-z0-9\\u4e00-\\u9fa5()/_\\-+%]", "");
        // 常见后缀清理
        s = s.replaceAll("(测定|检测|结果|计数)$", "");
        return s;
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> m = new HashMap<>();
        alias(m, "TC", "总胆固醇", "胆固醇");
        alias(m, "TG", "甘油三酯", "甘油三脂");
        alias(m, "LDL_C", "低密度脂蛋白", "低密度脂蛋白胆固醇", "ldl-c", "ldl_c", "ldlc");
        alias(m, "HDL_C", "高密度脂蛋白", "高密度脂蛋白胆固醇", "hdl-c", "hdl_c", "hdlc");
        alias(m, "FPG", "空腹血糖", "空腹静脉血糖", "空腹葡萄糖", "葡萄糖(空腹)");
        alias(m, "HBA1C", "糖化血红蛋白", "糖化血红蛋", "hba1c", "hb a1c");
        alias(m, "CR", "肌酐", "血肌酐", "scr");
        alias(m, "BUN", "尿素氮", "尿素");
        alias(m, "EGFR", "估算肾小球滤过率", "肾小球滤过率", "egfr");
        alias(m, "ALT", "丙氨酸氨基转移酶", "谷丙转氨酶", "alt");
        alias(m, "AST", "天门冬氨酸氨基转移酶", "谷草转氨酶", "ast");
        alias(m, "UA", "尿酸", "血尿酸");
        alias(m, "GGT", "γ-谷氨酰转移酶", "谷氨酰转移酶", "ggt");
        alias(m, "ALP", "碱性磷酸酶", "alp");
        alias(m, "TBIL", "总胆红素", "胆红素");
        alias(m, "DBIL", "直接胆红素");
        alias(m, "ALB", "白蛋白");
        alias(m, "TP", "总蛋白");

        // —— 血常规绝对值（先注册较长「百分比」别名，见 matchAlias 最长优先）——
        alias(
                m,
                "NEUT_PCT",
                "中性粒细胞百分比",
                "中性粒细胞百分率",
                "中性粒细胞比值",
                "中性粒细胞%",
                "neut%",
                "neutpct");
        alias(
                m,
                "LYMPH_PCT",
                "淋巴细胞百分比",
                "淋巴细胞百分率",
                "淋巴细胞比值",
                "淋巴细胞%",
                "lymph%",
                "lymphpct");
        alias(
                m,
                "MONO_PCT",
                "单核细胞百分比",
                "单核细胞百分率",
                "单核细胞比值",
                "单核细胞%",
                "mono%",
                "monopct");
        alias(
                m,
                "EOS_PCT",
                "嗜酸性粒细胞百分比",
                "嗜酸性粒细胞百分率",
                "嗜酸粒细胞百分比",
                "嗜酸性粒细胞%",
                "eos%",
                "eospct");
        alias(
                m,
                "BASO_PCT",
                "嗜碱性粒细胞百分比",
                "嗜碱性粒细胞百分率",
                "嗜碱粒细胞百分比",
                "嗜碱性粒细胞%",
                "baso%",
                "basopct");

        alias(m, "WBC", "白细胞", "白细胞计数", "白细胞数目", "白细胞总数", "白细胞总量", "wbc");
        alias(m, "RBC", "红细胞", "红细胞计数", "红细胞数目", "红细胞总数", "rbc");
        alias(m, "HGB", "血红蛋白", "血红蛋白量", "hb", "hgb");
        alias(m, "HCT", "红细胞比容", "红细胞压积", "hematocrit", "hct");
        alias(m, "PLT", "血小板", "血小板计数", "血小板数目", "血小板总数", "plt");
        alias(
                m,
                "NEUT",
                "中性粒细胞",
                "中性粒细胞计数",
                "中性粒细胞绝对值",
                "中性绝对数",
                "neut",
                "neut#");
        alias(
                m,
                "LYMPH",
                "淋巴细胞",
                "淋巴细胞计数",
                "淋巴细胞绝对值",
                "淋巴绝对数",
                "lymph",
                "lymph#");
        alias(
                m,
                "MONO",
                "单核细胞",
                "单核细胞计数",
                "单核细胞绝对值",
                "单核绝对数",
                "mono",
                "mono#");
        alias(
                m,
                "EOS",
                "嗜酸性粒细胞",
                "嗜酸性粒细胞计数",
                "嗜酸性粒细胞绝对值",
                "嗜酸粒细胞",
                "嗜酸绝对数",
                "eos",
                "eos#");
        alias(
                m,
                "BASO",
                "嗜碱性粒细胞",
                "嗜碱性粒细胞计数",
                "嗜碱性粒细胞绝对值",
                "嗜碱粒细胞",
                "嗜碱绝对数",
                "baso",
                "baso#");

        alias(m, "MCV", "平均红细胞体积", "平均红细胞容积", "mcv");
        alias(
                m,
                "MCH",
                "平均红细胞血红蛋白含量",
                "平均血红蛋白量",
                "平均红细胞血红蛋白量",
                "平均红细胞血红蛋白",
                "mch");
        alias(m, "MCHC", "平均红细胞血红蛋白浓度", "平均血红蛋白浓度", "mchc");
        alias(m, "RDW_CV", "红细胞分布宽度", "红细胞分布宽度cv", "红细胞体积分布宽度", "rdw", "rdw-cv", "rdwcv");
        alias(m, "RDW_SD", "红细胞分布宽度sd", "红细胞分布宽度-sd", "rdw-sd", "rdwsd");
        alias(m, "MPV", "平均血小板体积", "平均血小板容积", "mpv");
        alias(m, "PDW", "血小板分布宽度", "血小板体积分布宽度", "pdw");
        alias(m, "PCT", "血小板压积", "血小板比容", "血小板容积比", "pct");
        alias(m, "P_LCR", "大血小板比率", "大型血小板比率", "大血小板比例", "p-lcr", "plcr");

        alias(m, "PT", "凝血酶原时间", "pt");
        alias(m, "APTT", "活化部分凝血活酶时间", "aptt");
        alias(m, "FIB", "纤维蛋白原", "fib");
        alias(m, "DDIMER", "d-二聚体", "d二聚体", "ddimer");
        alias(m, "K", "钾", "血钾", "k");
        alias(m, "NA", "钠", "血钠", "na");
        alias(m, "CL", "氯", "血氯", "cl");
        alias(m, "CA", "钙", "血钙", "ca");
        alias(m, "P", "磷", "血磷");
        alias(m, "TSH", "促甲状腺激素", "tsh");
        alias(m, "FT3", "游离三碘甲状腺原氨酸", "游离t3", "ft3");
        alias(m, "FT4", "游离甲状腺素", "游离t4", "ft4");
        alias(m, "CRP", "c反应蛋白", "crp");
        alias(m, "ESR", "红细胞沉降率", "血沉", "esr");
        alias(m, "INSULIN", "胰岛素");
        alias(m, "C_PEPTIDE", "c肽", "c-peptide");
        alias(m, "AFP", "甲胎蛋白", "afp");
        alias(m, "CEA", "癌胚抗原", "cea");
        alias(m, "CA199", "糖类抗原199", "ca199", "ca-199");
        alias(m, "PSA", "前列腺特异性抗原", "psa");
        alias(m, "URINE_BLOOD", "尿潜血", "潜血(尿)", "尿隐血");
        alias(m, "URINE_PH", "尿液ph", "尿ph", "ph(尿)");
        alias(m, "URINE_SG", "尿比重", "比重(尿)");
        alias(m, "UACR", "尿白蛋白/肌酐比值", "尿微量白蛋白/肌酐", "uacr");
        alias(m, "URINE_PROTEIN", "尿蛋白", "蛋白质(尿)", "尿蛋白定性");
        alias(m, "URINE_GLUCOSE", "尿糖", "葡萄糖(尿)");
        alias(m, "URINE_KETONE", "尿酮体", "酮体(尿)");
        return m;
    }

    private static void alias(Map<String, String> m, String code, String... names) {
        for (String name : names) {
            m.put(normalize(name), code);
        }
    }
}
