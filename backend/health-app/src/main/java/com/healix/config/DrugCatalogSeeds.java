package com.healix.config;

import com.healix.common.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 慢病/常用药小库种子（约 80 条，幂等写入 sys_dict drugCatalog）。 */
public final class DrugCatalogSeeds {

    @FunctionalInterface
    public interface SeedFn {
        void seed(String code, String displayName, int sort, String contentJson);
    }

    private DrugCatalogSeeds() {}

    public static void ensure(SeedFn seed) {
        int s = 1000;

        // 降压
        seedDrug(seed, "AMLODIPINE_5", "氨氯地平片", s -= 1, "氨氯地平", "5mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "络活喜");
        seedDrug(seed, "VALSARTAN_80", "缬沙坦胶囊", s -= 1, "缬沙坦", "80mg×28粒", "胶囊", "CAPSULE", "1", "ORAL", "QD", "BP", "代文");
        seedDrug(seed, "IRBESARTAN_150", "厄贝沙坦片", s -= 1, "厄贝沙坦", "150mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "安博维");
        seedDrug(seed, "NIFEDIPINE_CR_30", "硝苯地平控释片", s -= 1, "硝苯地平", "30mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "拜新同");
        seedDrug(seed, "METOPROLOL_25", "美托洛尔缓释片", s -= 1, "美托洛尔", "25mg×20片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "倍他乐克");
        seedDrug(seed, "BISOPROLOL_5", "比索洛尔片", s -= 1, "比索洛尔", "5mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "康忻");
        seedDrug(seed, "HCTZ_25", "氢氯噻嗪片", s -= 1, "氢氯噻嗪", "25mg×100片", "片剂", "TABLET", "1", "ORAL", "QD", "BP");
        seedDrug(seed, "PERINDOPRIL_4", "培哚普利片", s -= 1, "培哚普利", "4mg×30片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "雅施达");
        seedDrug(seed, "BENAZEPRIL_10", "贝那普利片", s -= 1, "贝那普利", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "洛汀新");
        seedDrug(seed, "LOSARTAN_50", "氯沙坦钾片", s -= 1, "氯沙坦", "50mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "科素亚");
        seedDrug(seed, "TELMISARTAN_40", "替米沙坦片", s -= 1, "替米沙坦", "40mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "美卡素");
        seedDrug(seed, "CAPTOPRIL_25", "卡托普利片", s -= 1, "卡托普利", "25mg×100片", "片剂", "TABLET", "1", "ORAL", "TID", "BP", "开博通");
        seedDrug(seed, "INDAPAMIDE_25", "吲达帕胺片", s -= 1, "吲达帕胺", "2.5mg×30片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "纳催离");
        seedDrug(seed, "SPIRONOLACTONE_20", "螺内酯片", s -= 1, "螺内酯", "20mg×100片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "安体舒通");
        seedDrug(seed, "FELODIPINE_5", "非洛地平缓释片", s -= 1, "非洛地平", "5mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "波依定");
        seedDrug(seed, "NITRENDIPINE_10", "尼群地平片", s -= 1, "尼群地平", "10mg×100片", "片剂", "TABLET", "1", "ORAL", "BID", "BP");
        seedDrug(seed, "CARVEDILOL_625", "卡维地洛片", s -= 1, "卡维地洛", "6.25mg×28片", "片剂", "TABLET", "1", "ORAL", "BID", "BP", "达利全");
        seedDrug(seed, "TERAZOSIN_2", "特拉唑嗪片", s -= 1, "特拉唑嗪", "2mg×28片", "片剂", "TABLET", "1", "ORAL", "QN", "BP", "高特灵");
        seedDrug(seed, "FUROSEMIDE_20", "呋塞米片", s -= 1, "呋塞米", "20mg×100片", "片剂", "TABLET", "1", "ORAL", "QD", "BP", "速尿");

        // 降糖
        seedDrug(seed, "METFORMIN_05", "二甲双胍片", s -= 1, "二甲双胍", "0.5g×48片", "片剂", "TABLET", "1", "ORAL", "BID", "DM", "格华止");
        seedDrug(seed, "METFORMIN_ER_05", "二甲双胍缓释片", s -= 1, "二甲双胍", "0.5g×30片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "美迪康");
        seedDrug(seed, "GLIMEPIRIDE_2", "格列美脲片", s -= 1, "格列美脲", "2mg×30片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "亚莫利");
        seedDrug(seed, "GLICLAZIDE_MR_30", "格列齐特缓释片", s -= 1, "格列齐特", "30mg×30片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "达美康");
        seedDrug(seed, "GLICLAZIDE_80", "格列齐特片", s -= 1, "格列齐特", "80mg×60片", "片剂", "TABLET", "1", "ORAL", "BID", "DM");
        seedDrug(seed, "GLIPIZIDE_5", "格列吡嗪片", s -= 1, "格列吡嗪", "5mg×100片", "片剂", "TABLET", "1", "ORAL", "BID", "DM", "美吡达");
        seedDrug(seed, "ACARBOSE_50", "阿卡波糖片", s -= 1, "阿卡波糖", "50mg×90片", "片剂", "TABLET", "1", "ORAL", "TID", "DM", "拜唐苹");
        seedDrug(seed, "SITAGLIPTIN_100", "西格列汀片", s -= 1, "西格列汀", "100mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "捷诺维");
        seedDrug(seed, "LINAGLIPTIN_5", "利格列汀片", s -= 1, "利格列汀", "5mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "欧唐宁");
        seedDrug(seed, "DAPAGLIFLOZIN_10", "达格列净片", s -= 1, "达格列净", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "安达唐");
        seedDrug(seed, "EMPAGLIFLOZIN_10", "恩格列净片", s -= 1, "恩格列净", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "欧唐静");
        seedDrug(seed, "PIOGLITAZONE_15", "吡格列酮片", s -= 1, "吡格列酮", "15mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "DM", "艾可拓");
        seedDrug(seed, "REPAGLINIDE_1", "瑞格列奈片", s -= 1, "瑞格列奈", "1mg×60片", "片剂", "TABLET", "1", "ORAL", "TID", "DM", "诺和龙");
        seedDrug(seed, "VOGLIBOSE_02", "伏格列波糖片", s -= 1, "伏格列波糖", "0.2mg×90片", "片剂", "TABLET", "1", "ORAL", "TID", "DM", "倍欣");
        seedDrug(seed, "INSULIN_GLARGINE", "甘精胰岛素注射液", s -= 1, "甘精胰岛素", "3ml:300单位", "注射液", "BOTTLE", "1", "INJECTION", "QD", "DM", "来得时", "长秀霖");
        seedDrug(seed, "INSULIN_ASPART", "门冬胰岛素注射液", s -= 1, "门冬胰岛素", "3ml:300单位", "注射液", "BOTTLE", "1", "INJECTION", "TID", "DM", "诺和锐");
        seedDrug(seed, "INSULIN_REGULAR", "人胰岛素注射液", s -= 1, "人胰岛素", "3ml:300单位", "注射液", "BOTTLE", "1", "INJECTION", "TID", "DM", "诺和灵R");

        // 降脂
        seedDrug(seed, "ATORVASTATIN_20", "阿托伐他汀钙片", s -= 1, "阿托伐他汀", "20mg×28片", "片剂", "TABLET", "1", "ORAL", "QN", "LIPID", "立普妥");
        seedDrug(seed, "ROSUVASTATIN_10", "瑞舒伐他汀钙片", s -= 1, "瑞舒伐他汀", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QN", "LIPID", "可定");
        seedDrug(seed, "SIMVASTATIN_20", "辛伐他汀片", s -= 1, "辛伐他汀", "20mg×28片", "片剂", "TABLET", "1", "ORAL", "QN", "LIPID", "舒降之");
        seedDrug(seed, "PRAVASTATIN_20", "普伐他汀钠片", s -= 1, "普伐他汀", "20mg×28片", "片剂", "TABLET", "1", "ORAL", "QN", "LIPID", "美百乐镇");
        seedDrug(seed, "FENOFIBRATE_02", "非诺贝特胶囊", s -= 1, "非诺贝特", "0.2g×30粒", "胶囊", "CAPSULE", "1", "ORAL", "QD", "LIPID", "力平之");
        seedDrug(seed, "EZETIMIBE_10", "依折麦布片", s -= 1, "依折麦布", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "LIPID", "益适纯");

        // 心血管 / 抗栓
        seedDrug(seed, "ASPIRIN_100", "阿司匹林肠溶片", s -= 1, "阿司匹林", "100mg×30片", "片剂", "TABLET", "1", "ORAL", "QD", "CV", "拜阿司匹灵");
        seedDrug(seed, "CLOPIDOGREL_75", "硫酸氢氯吡格雷片", s -= 1, "氯吡格雷", "75mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "CV", "波立维", "泰嘉");
        seedDrug(seed, "TICAGRELOR_90", "替格瑞洛片", s -= 1, "替格瑞洛", "90mg×28片", "片剂", "TABLET", "1", "ORAL", "BID", "CV", "倍林达");
        seedDrug(seed, "ISOSORBIDE_MONO_40", "单硝酸异山梨酯缓释片", s -= 1, "单硝酸异山梨酯", "40mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "CV", "欣康");
        seedDrug(seed, "NITROGLYCERIN_05", "硝酸甘油片", s -= 1, "硝酸甘油", "0.5mg×100片", "片剂", "TABLET", "1", "ORAL", "PRN", "CV");
        seedDrug(seed, "WARFARIN_3", "华法林钠片", s -= 1, "华法林", "3mg×100片", "片剂", "TABLET", "1", "ORAL", "QD", "CV");
        seedDrug(seed, "RIVAROXABAN_10", "利伐沙班片", s -= 1, "利伐沙班", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "CV", "拜瑞妥");
        seedDrug(seed, "DABIGATRAN_110", "达比加群酯胶囊", s -= 1, "达比加群", "110mg×28粒", "胶囊", "CAPSULE", "1", "ORAL", "BID", "CV", "泰毕全");

        // 痛风
        seedDrug(seed, "ALLOPURINOL_100", "别嘌醇片", s -= 1, "别嘌醇", "100mg×100片", "片剂", "TABLET", "1", "ORAL", "QD", "GOUT");
        seedDrug(seed, "FEBUXOSTAT_40", "非布司他片", s -= 1, "非布司他", "40mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "GOUT", "优立通");
        seedDrug(seed, "COLCHICINE_05", "秋水仙碱片", s -= 1, "秋水仙碱", "0.5mg×20片", "片剂", "TABLET", "1", "ORAL", "PRN", "GOUT");

        // 甲状腺
        seedDrug(seed, "LEVOTHYROXINE_50", "左甲状腺素钠片", s -= 1, "左甲状腺素", "50μg×100片", "片剂", "TABLET", "1", "ORAL", "QD", "THYROID", "优甲乐");

        // 呼吸
        seedDrug(seed, "SALBUTAMOL_INH", "沙丁胺醇气雾剂", s -= 1, "沙丁胺醇", "100μg×200揿", "气雾剂", "SPRAY", "1", "INHALATION", "PRN", "RESP", "万托林");
        seedDrug(seed, "BUDESONIDE_FORMOTEROL", "布地奈德福莫特罗粉吸入剂", s -= 1, "布地奈德/福莫特罗", "160/4.5μg×120吸", "吸入剂", "SPRAY", "1", "INHALATION", "BID", "RESP", "信必可");
        seedDrug(seed, "TIOTROPIUM_INH", "噻托溴铵粉吸入剂", s -= 1, "噻托溴铵", "18μg×30粒", "吸入剂", "CAPSULE", "1", "INHALATION", "QD", "RESP", "思力华");

        // 消化 / 其他慢病
        seedDrug(seed, "OMEPRAZOLE_20", "奥美拉唑肠溶胶囊", s -= 1, "奥美拉唑", "20mg×28粒", "胶囊", "CAPSULE", "1", "ORAL", "QD", "GI", "洛赛克");
        seedDrug(seed, "RABEPRAZOLE_10", "雷贝拉唑钠肠溶片", s -= 1, "雷贝拉唑", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QD", "GI", "波利特");
        seedDrug(seed, "MONTELUKAST_10", "孟鲁司特钠片", s -= 1, "孟鲁司特", "10mg×28片", "片剂", "TABLET", "1", "ORAL", "QN", "RESP", "顺尔宁");

        // 抗感染 / 常用药
        seedDrug(seed, "AMOXICILLIN_CAP_05", "阿莫西林胶囊", s -= 1, "阿莫西林", "0.5g×24粒", "胶囊", "CAPSULE", "1", "ORAL", "TID", "ANTI", "阿莫西林");
        seedDrug(seed, "CEFIXIME_01", "头孢克肟胶囊", s -= 1, "头孢克肟", "0.1g×12粒", "胶囊", "CAPSULE", "1", "ORAL", "BID", "ANTI");
        seedDrug(seed, "AZITHROMYCIN_025", "阿奇霉素片", s -= 1, "阿奇霉素", "0.25g×6片", "片剂", "TABLET", "1", "ORAL", "QD", "ANTI", "希舒美");
        seedDrug(seed, "LEVOFLOXACIN_05", "左氧氟沙星片", s -= 1, "左氧氟沙星", "0.5g×28片", "片剂", "TABLET", "1", "ORAL", "QD", "ANTI", "可乐必妥");

        // 解热镇痛 / 对症
        seedDrug(seed, "IBUPROFEN_02", "布洛芬缓释胶囊", s -= 1, "布洛芬", "0.2g×24粒", "胶囊", "CAPSULE", "1", "ORAL", "PRN", "SYM", "芬必得");
        seedDrug(seed, "PARACETAMOL_05", "对乙酰氨基酚片", s -= 1, "对乙酰氨基酚", "0.5g×24片", "片剂", "TABLET", "1", "ORAL", "PRN", "SYM", "泰诺林", "扑热息痛");
        seedDrug(seed, "LORATADINE_10", "氯雷他定片", s -= 1, "氯雷他定", "10mg×12片", "片剂", "TABLET", "1", "ORAL", "QD", "SYM", "开瑞坦");
        seedDrug(seed, "AMBROXOL_ORAL", "氨溴索口服溶液", s -= 1, "氨溴索", "100ml:0.3g", "口服液", "ML", "10", "ORAL", "TID", "SYM", "沐舒坦");

        // 补充剂 / 其他
        seedDrug(seed, "CALCIUM_D3", "碳酸钙D3片", s -= 1, "碳酸钙/维生素D3", "600mg×60片", "片剂", "TABLET", "1", "ORAL", "QD", "SUPP", "钙尔奇D");
        seedDrug(seed, "VITAMIN_D3", "维生素D3软胶囊", s -= 1, "维生素D3", "400IU×36粒", "胶囊", "CAPSULE", "1", "ORAL", "QD", "SUPP");
        seedDrug(seed, "FOLIC_ACID_5", "叶酸片", s -= 1, "叶酸", "5mg×100片", "片剂", "TABLET", "1", "ORAL", "QD", "SUPP");
        seedDrug(seed, "FE_SUCROSE", "蔗糖铁注射液", s -= 1, "蔗糖铁", "5ml:100mg", "注射液", "BOTTLE", "1", "INJECTION", "PRN", "SUPP");
    }

    private static void seedDrug(
            SeedFn seed,
            String code,
            String displayName,
            int sort,
            String genericName,
            String spec,
            String dosageForm,
            String doseUnit,
            String doseAmount,
            String usage,
            String frequency,
            String category,
            String... aliases) {
        seed.seed(code, displayName, sort, contentJson(
                genericName, spec, dosageForm, doseUnit, doseAmount, usage, frequency, category, aliases));
    }

    private static String contentJson(
            String genericName,
            String spec,
            String dosageForm,
            String doseUnit,
            String doseAmount,
            String usage,
            String frequency,
            String category,
            String... aliases) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("genericName", genericName);
        m.put("spec", spec);
        m.put("dosageForm", dosageForm);
        m.put("defaultDoseUnit", doseUnit);
        m.put("defaultDoseAmount", doseAmount);
        m.put("defaultUsageMethod", usage);
        m.put("defaultFrequency", frequency);
        m.put("category", category);
        if (aliases != null && aliases.length > 0) {
            List<String> list = new ArrayList<>();
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    list.add(alias);
                }
            }
            if (!list.isEmpty()) {
                m.put("aliases", list);
            }
        }
        return JsonUtils.toJson(m);
    }
}
