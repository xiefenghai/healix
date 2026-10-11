package com.healix.core.observation.support;

import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.service.DictService;
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
        if (ALIASES.containsKey(normalized)) {
            return Optional.of(ALIASES.get(normalized));
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
        for (DictItemDto item : catalog()) {
            String desc = normalize(item.getDictCodeDesc());
            if (StringUtils.hasText(desc) && (normalized.contains(desc) || desc.contains(normalized))) {
                return Optional.of(item.getDictCode());
            }
        }
        return Optional.empty();
    }

    public boolean isKnownCode(String itemCode) {
        if (!StringUtils.hasText(itemCode)) {
            return false;
        }
        return catalog().stream().anyMatch(d -> itemCode.equals(d.getDictCode()));
    }

    public String resolveName(String itemCode) {
        return catalog().stream()
                .filter(d -> itemCode.equals(d.getDictCode()))
                .map(DictItemDto::getDictCodeDesc)
                .findFirst()
                .orElse(itemCode);
    }

    private List<DictItemDto> catalog() {
        return dictService.listMerged(DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), "labItemCode");
    }

    private static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('μ', 'u')
                .replace("（", "(")
                .replace("）", ")")
                .replaceAll("\\s+", "")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5()/_\\-+%]", "");
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
        alias(m, "WBC", "白细胞", "白细胞计数", "wbc");
        alias(m, "RBC", "红细胞", "红细胞计数", "rbc");
        alias(m, "HGB", "血红蛋白", "hb", "hgb");
        alias(m, "HCT", "红细胞比容", "hematocrit", "hct");
        alias(m, "PLT", "血小板", "血小板计数", "plt");
        alias(m, "NEUT", "中性粒细胞", "中性粒细胞计数", "neut");
        alias(m, "LYMPH", "淋巴细胞", "淋巴细胞计数", "lymph");
        alias(m, "MONO", "单核细胞", "单核细胞计数", "mono");
        alias(m, "BASO", "嗜碱性粒细胞", "嗜碱性粒细胞计数", "baso");
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
        alias(m, "EOS", "嗜酸性粒细胞", "嗜酸性粒细胞计数", "嗜酸粒细胞");
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
