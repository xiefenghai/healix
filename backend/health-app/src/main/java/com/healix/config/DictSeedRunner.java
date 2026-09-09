package com.healix.config;

import com.healix.common.domain.EntityMeta;
import com.healix.core.dict.domain.SysDict;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.mapper.SysDictMapper;
import com.healix.core.dict.service.DictService;
import com.healix.core.dict.support.DictFieldSchemas;
import com.healix.core.metadata.enums.MetaDataCodeEnum;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DictSeedRunner implements ApplicationRunner {

    private final SysDictMapper sysDictMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (sysDictMapper.countByTenant(DictService.PLATFORM_TENANT) == 0) {
            seedDisease("diabetes", "糖尿病", 100);
            seedDisease("hypertension", "高血压", 90);
            seedPresentIllnessOptions();
            seedOption("appetiteLevel", "NORMAL", "正常", 100, null);
            seedOption("appetiteLevel", "POOR", "较差", 80, null);
            seedOption("exerciseFrequency", "WEEKLY_3", "每周≥3次", 100, null);
            seedOption("exerciseFrequency", "WEEKLY_1", "每周1-2次", 80, null);
            seedOption("sleepQuality", "GOOD", "良好", 100, null);
            seedOption("sleepQuality", "POOR", "较差", 80, null);
            seedOption("smoking", "NEVER", "从不", 100, null);
            seedOption("smoking", "OCCASIONAL", "偶尔", 80, null);
            seedOption("drinking", "NEVER", "从不", 100, null);
            seedOption("drinking", "OCCASIONAL", "偶尔", 80, null);
            seedDoseUnitOptions();

            seedBasicField(
                    "presentIllness",
                    "现有疾病",
                    DictFieldSchemas.multiSelectField(
                            MetaDataCodeEnum.BASIC_PRESENT_ILLNESS, "现有疾病", "presentIllness"),
                    100);
            seedBasicField(
                    "presentIllnessOther",
                    "现有疾病-其他",
                    DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_PRESENT_ILLNESS_OTHER, "现有疾病-其他"),
                    95);
            seedBasicField(
                    "familyHistory",
                    "家族史",
                    DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_FAMILY_HISTORY, "家族史"),
                    90);
            seedBasicField(
                    "pastHistory",
                    "既往史",
                    DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_PAST_HISTORY, "既往史（摘要）"),
                    85);
            seedBasicField(
                    "pastHistoryItems",
                    "既往史明细",
                    DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_PAST_HISTORY_ITEMS, "既往史结构化明细"),
                    84);
            seedBasicField(
                    "diet",
                    "饮食情况",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_DIET,
                            "饮食情况",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("appetite", "食欲", "appetiteLevel"),
                                    DictFieldSchemas.subTextField("preference", "饮食偏好"),
                                    DictFieldSchemas.subTextField("note", "备注"))),
                    70);
            seedBasicField(
                    "exercise",
                    "运动情况",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_EXERCISE,
                            "运动情况",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("frequency", "频率", "exerciseFrequency"),
                                    DictFieldSchemas.subNumberField("durationMin", "时长(分钟)"),
                                    DictFieldSchemas.subTextField("type", "类型"))),
                    60);
            seedBasicField(
                    "sleep",
                    "睡眠情况",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_SLEEP,
                            "睡眠情况",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("quality", "质量", "sleepQuality"),
                                    DictFieldSchemas.subNumberField("hours", "时长(小时)"),
                                    DictFieldSchemas.subTextField("note", "备注"))),
                    50);
            seedBasicField(
                    "lifestyle",
                    "生活习惯",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_LIFESTYLE,
                            "生活习惯",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("smoking", "吸烟", "smoking"),
                                    DictFieldSchemas.subSelectField("drinking", "饮酒", "drinking"),
                                    DictFieldSchemas.subTextField("note", "备注"))),
                    40);
            seedDiseaseField(
                    "diabetes",
                    "diagnosisDate",
                    "确诊时间",
                    DictFieldSchemas.textField(MetaDataCodeEnum.DISEASE_DIABETES_DIAGNOSIS_DATE, "确诊时间"),
                    100);
            seedDiseaseField(
                    "hypertension",
                    "diagnosisDate",
                    "确诊时间",
                    DictFieldSchemas.textField(
                            MetaDataCodeEnum.DISEASE_HYPERTENSION_DIAGNOSIS_DATE, "确诊时间"),
                    100);
            log.warn("Bootstrapped platform sys_dict seed data");
        }
        // 已有库也可幂等补齐糖尿病 / 高血压扩展字段
        ensureDiabetesArchiveExtensions();
        ensureHypertensionArchiveExtensions();
        ensureLifestyleSocialHistoryOptions();
        ensureDoseUnitOptions();
        ensureMedicationUsageOptions();
        ensureMedicationFrequencyOptions();
        ensureDrugCatalog();
        ensureObservationDicts();
    }

    /** 生活方式 / 社会史选项（幂等；对齐临床结构化字段）。 */
    private void ensureLifestyleSocialHistoryOptions() {
        seedOptionIfAbsent("smoking", "NEVER", "从不", 100);
        seedOptionIfAbsent("smoking", "CURRENT", "当前吸烟", 90);
        seedOptionIfAbsent("smoking", "FORMER", "已戒烟", 80);
        seedOptionIfAbsent("smoking", "OCCASIONAL", "偶尔", 70);

        seedOptionIfAbsent("drinking", "NEVER", "从不", 100);
        seedOptionIfAbsent("drinking", "CURRENT", "当前饮酒", 90);
        seedOptionIfAbsent("drinking", "FORMER", "已戒酒", 80);
        seedOptionIfAbsent("drinking", "OCCASIONAL", "偶尔", 70);

        seedOptionIfAbsent("drinkingFrequency", "DAILY", "每天", 100);
        seedOptionIfAbsent("drinkingFrequency", "WEEKLY_3", "每周≥3次", 90);
        seedOptionIfAbsent("drinkingFrequency", "WEEKLY_1_2", "每周1-2次", 80);
        seedOptionIfAbsent("drinkingFrequency", "MONTHLY", "每月数次", 70);

        seedOptionIfAbsent("exerciseFrequency", "NONE", "基本不运动", 110);
        seedOptionIfAbsent("exerciseFrequency", "WEEKLY_3", "每周≥3次", 100);
        seedOptionIfAbsent("exerciseFrequency", "WEEKLY_1", "每周1-2次", 80);
        seedOptionIfAbsent("exerciseFrequency", "DAILY", "每天", 70);

        seedOptionIfAbsent("exerciseIntensity", "LIGHT", "轻度", 100);
        seedOptionIfAbsent("exerciseIntensity", "MODERATE", "中度", 90);
        seedOptionIfAbsent("exerciseIntensity", "VIGOROUS", "重度", 80);

        seedOptionIfAbsent("appetiteLevel", "GOOD", "好", 110);
        seedOptionIfAbsent("appetiteLevel", "NORMAL", "正常", 100);
        seedOptionIfAbsent("appetiteLevel", "FAIR", "一般", 90);
        seedOptionIfAbsent("appetiteLevel", "POOR", "较差", 80);

        seedOptionIfAbsent("dietHabit", "GOOD", "好", 100);
        seedOptionIfAbsent("dietHabit", "FAIR", "一般", 90);
        seedOptionIfAbsent("dietHabit", "POOR", "差", 80);

        seedOptionIfAbsent("dietType", "NORMAL", "普通饮食", 100);
        seedOptionIfAbsent("dietType", "VEGETARIAN", "素食", 90);
        seedOptionIfAbsent("dietType", "LOW_SALT", "低盐", 80);
        seedOptionIfAbsent("dietType", "LOW_SUGAR", "低糖", 70);
        seedOptionIfAbsent("dietType", "LOW_SALT_SUGAR", "低盐低糖", 60);
        seedOptionIfAbsent("dietType", "OTHER", "其他", 20);

        seedOptionIfAbsent("sleepQuality", "GOOD", "良好", 100);
        seedOptionIfAbsent("sleepQuality", "FAIR", "一般", 90);
        seedOptionIfAbsent("sleepQuality", "POOR", "较差", 80);

        seedOptionIfAbsent("sleepDisorder", "NONE", "无", 100);
        seedOptionIfAbsent("sleepDisorder", "INSOMNIA", "失眠", 90);
        seedOptionIfAbsent("sleepDisorder", "SNORING", "打鼾/睡眠呼吸问题", 80);
        seedOptionIfAbsent("sleepDisorder", "OTHER", "其他", 20);

        log.warn("Ensured lifestyle social-history dict options");
    }

    /** 平台药品小库（幂等）。 */
    private void ensureDrugCatalog() {
        DrugCatalogSeeds.ensure((code, displayName, sort, contentJson) ->
                seedOptionIfAbsent("drugCatalog", code, displayName, sort, contentJson));
    }

    /** 健康数据：指标 / 检验 / 检查字典（幂等）。 */
    private void ensureObservationDicts() {
        seedOptionIfAbsent(
                "metricType",
                "BLOOD_PRESSURE_SYS",
                "收缩压",
                100,
                "{\"unit\":\"mmHg\",\"group\":\"BP\",\"role\":\"SYS\",\"refLow\":90,\"refHigh\":139}");
        seedOptionIfAbsent(
                "metricType",
                "BLOOD_PRESSURE_DIA",
                "舒张压",
                95,
                "{\"unit\":\"mmHg\",\"group\":\"BP\",\"role\":\"DIA\",\"refLow\":60,\"refHigh\":89}");
        seedOptionIfAbsent(
                "metricType",
                "BLOOD_GLUCOSE",
                "指尖血糖",
                90,
                "{\"unit\":\"mmol/L\",\"refByMeal\":{\"FASTING\":{\"refLow\":3.9,\"refHigh\":6.1},\"POSTPRANDIAL\":{\"refHigh\":7.8},\"RANDOM\":{\"refHigh\":11.1}}}");
        seedOptionIfAbsent("metricType", "HEIGHT", "身高", 85, "{\"unit\":\"cm\"}");
        seedOptionIfAbsent("metricType", "WEIGHT", "体重", 80, "{\"unit\":\"kg\"}");
        seedOptionIfAbsent(
                "metricType",
                "WAIST",
                "腰围",
                75,
                "{\"unit\":\"cm\",\"refHighMale\":90,\"refHighFemale\":85}");
        seedOptionIfAbsent(
                "metricType",
                "HEART_RATE",
                "心率",
                70,
                "{\"unit\":\"bpm\",\"refLow\":60,\"refHigh\":100}");
        seedOptionIfAbsent("metricType", "TEMPERATURE", "体温", 65, "{\"unit\":\"℃\"}");
        seedOptionIfAbsent("metricType", "STEPS", "步数", 60, "{\"unit\":\"步\"}");
        seedOptionIfAbsent("metricType", "SLEEP_HOURS", "睡眠时长", 55, "{\"unit\":\"h\"}");

        seedOptionIfAbsent("bpContext", "CLINIC", "诊室", 100);
        seedOptionIfAbsent("bpContext", "HOME", "家庭", 90);
        seedOptionIfAbsent("mealContext", "FASTING", "空腹", 100);
        seedOptionIfAbsent("mealContext", "POSTPRANDIAL", "餐后", 90);
        seedOptionIfAbsent("mealContext", "RANDOM", "随机", 80);

        seedLabItem("TC", "总胆固醇", 1000, "mmol/L", 0, 5.2);
        seedLabItem("TG", "甘油三酯", 990, "mmol/L", 0, 1.7);
        seedLabItem("LDL_C", "低密度脂蛋白胆固醇", 980, "mmol/L", 0, 3.4);
        seedLabItem("HDL_C", "高密度脂蛋白胆固醇", 970, "mmol/L", 1.0, null);

        seedLabItem("FPG", "空腹静脉血糖", 960, "mmol/L", 3.9, 6.1);
        seedLabItem("HBA1C", "糖化血红蛋白", 950, "%", 0, 6.0);
        seedLabItem("INSULIN", "胰岛素", 940, "mIU/L", 2.6, 24.9);
        seedLabItem("C_PEPTIDE", "C肽", 930, "ng/mL", 0.8, 4.0);

        seedLabItem("CR", "肌酐", 920, "μmol/L", 44.0, 133.0);
        seedLabItem("BUN", "尿素氮", 910, "mmol/L", 2.9, 8.2);
        seedLabItem("EGFR", "估算肾小球滤过率", 900, "mL/min/1.73m²", 90.0, null);
        seedLabItem("UA", "尿酸", 890, "μmol/L", 155.0, 428.0);

        seedLabItem("ALT", "丙氨酸氨基转移酶", 880, "U/L", 0, 40.0);
        seedLabItem("AST", "天门冬氨酸氨基转移酶", 870, "U/L", 0, 40.0);
        seedLabItem("GGT", "γ-谷氨酰转移酶", 860, "U/L", 0, 50.0);
        seedLabItem("ALP", "碱性磷酸酶", 850, "U/L", 40.0, 150.0);
        seedLabItem("TBIL", "总胆红素", 840, "μmol/L", 0, 21.0);
        seedLabItem("DBIL", "直接胆红素", 830, "μmol/L", 0, 8.0);
        seedLabItem("ALB", "白蛋白", 820, "g/L", 40.0, 55.0);
        seedLabItem("TP", "总蛋白", 810, "g/L", 65.0, 85.0);

        seedLabItem("WBC", "白细胞计数", 800, "×10^9/L", 3.5, 9.5);
        seedLabItem("RBC", "红细胞计数", 790, "×10^12/L", 3.8, 5.8);
        seedLabItem("HGB", "血红蛋白", 780, "g/L", 115.0, 175.0);
        seedLabItem("HCT", "红细胞比容", 770, "%", 35.0, 50.0);
        seedLabItem("PLT", "血小板计数", 760, "×10^9/L", 125.0, 350.0);
        seedLabItem("NEUT", "中性粒细胞计数", 750, "×10^9/L", 1.8, 6.3);
        seedLabItem("LYMPH", "淋巴细胞计数", 740, "×10^9/L", 1.1, 3.2);
        seedLabItem("MONO", "单核细胞计数", 730, "×10^9/L", 0.1, 0.6);
        seedLabItem("EOS", "嗜酸性粒细胞计数", 720, "×10^9/L", 0.02, 0.5);
        seedLabItem("BASO", "嗜碱性粒细胞计数", 710, "×10^9/L", 0, 0.06);

        seedLabItem("PT", "凝血酶原时间", 700, "s", 11.0, 14.0);
        seedLabItem("APTT", "活化部分凝血活酶时间", 690, "s", 25.0, 35.0);
        seedLabItem("FIB", "纤维蛋白原", 680, "g/L", 2.0, 4.0);
        seedLabItem("DDIMER", "D-二聚体", 670, "mg/L", 0, 0.5);

        seedLabItem("K", "钾", 660, "mmol/L", 3.5, 5.5);
        seedLabItem("NA", "钠", 650, "mmol/L", 136.0, 145.0);
        seedLabItem("CL", "氯", 640, "mmol/L", 96.0, 106.0);
        seedLabItem("CA", "钙", 630, "mmol/L", 2.1, 2.7);
        seedLabItem("P", "磷", 620, "mmol/L", 0.81, 1.45);

        seedLabItem("TSH", "促甲状腺激素", 610, "mIU/L", 0.27, 4.2);
        seedLabItem("FT3", "游离三碘甲状腺原氨酸", 600, "pmol/L", 3.1, 6.8);
        seedLabItem("FT4", "游离甲状腺素", 590, "pmol/L", 12.0, 22.0);

        seedLabItem("CRP", "C反应蛋白", 580, "mg/L", 0, 10.0);
        seedLabItem("ESR", "红细胞沉降率", 570, "mm/h", 0, 20.0);

        seedLabItem("AFP", "甲胎蛋白", 560, "ng/mL", 0, 7.0);
        seedLabItem("CEA", "癌胚抗原", 550, "ng/mL", 0, 5.0);
        seedLabItem("CA199", "糖类抗原199", 540, "U/mL", 0, 37.0);
        seedLabItem("PSA", "前列腺特异性抗原", 530, "ng/mL", 0, 4.0);

        seedLabItem("UACR", "尿白蛋白/肌酐比值", 520, "mg/g", 0, 30.0);
        seedLabItem("URINE_PH", "尿液pH", 510, "", 4.5, 8.0);
        seedLabItem("URINE_SG", "尿比重", 500, "", 1.003, 1.030);
        seedOptionIfAbsent(
                "labItemCode",
                "URINE_PROTEIN",
                "尿蛋白",
                490,
                "{\"unit\":\"\",\"qualitative\":true}");
        seedOptionIfAbsent(
                "labItemCode",
                "URINE_GLUCOSE",
                "尿糖",
                480,
                "{\"unit\":\"\",\"qualitative\":true}");
        seedOptionIfAbsent(
                "labItemCode", "URINE_KETONE", "尿酮体", 470, "{\"unit\":\"\",\"qualitative\":true}");
        seedOptionIfAbsent(
                "labItemCode", "URINE_BLOOD", "尿潜血", 460, "{\"unit\":\"\",\"qualitative\":true}");

        seedOptionIfAbsent("examType", "ECG", "心电图", 1000);
        seedOptionIfAbsent("examType", "UCG", "心脏彩超", 990);
        seedOptionIfAbsent("examType", "CAROTID_US", "颈动脉彩超", 980);
        seedOptionIfAbsent("examType", "ABDOMINAL_US", "腹部超声", 970);
        seedOptionIfAbsent("examType", "THYROID_US", "甲状腺超声", 960);
        seedOptionIfAbsent("examType", "CHEST_IMAGING", "胸部影像", 950);
        seedOptionIfAbsent("examType", "ABDOMINAL_CT", "腹部CT", 940);
        seedOptionIfAbsent("examType", "PFT", "肺功能", 930);
        seedOptionIfAbsent("examType", "BMD", "骨密度", 920);
        seedOptionIfAbsent("examType", "FUNDUS", "眼底检查", 910);
    }

    private void seedLabItem(String code, String desc, int sort, String unit, Number refLow, Number refHigh) {
        StringBuilder content = new StringBuilder("{\"unit\":\"").append(unit).append("\"");
        if (refLow != null) {
            content.append(",\"refLow\":").append(refLow);
        }
        if (refHigh != null) {
            content.append(",\"refHigh\":").append(refHigh);
        }
        content.append("}");
        seedOptionIfAbsent("labItemCode", code, desc, sort, content.toString());
    }

    private void seedDoseUnitOptions() {
        seedOption("doseUnit", "G", "g", 100, null);
        seedOption("doseUnit", "MG", "mg", 90, null);
        seedOption("doseUnit", "ML", "ml", 80, null);
        seedOption("doseUnit", "TABLET", "片", 70, null);
        seedOption("doseUnit", "CAPSULE", "粒", 60, null);
        seedOption("doseUnit", "BAG", "袋", 50, null);
        seedOption("doseUnit", "BOTTLE", "支", 40, null);
        seedOption("doseUnit", "SPRAY", "喷", 30, null);
        seedOption("doseUnit", "OTHER", "其他", 20, null);
    }

    private void ensureDoseUnitOptions() {
        seedOptionIfAbsent("doseUnit", "G", "g", 100);
        seedOptionIfAbsent("doseUnit", "MG", "mg", 90);
        seedOptionIfAbsent("doseUnit", "ML", "ml", 80);
        seedOptionIfAbsent("doseUnit", "TABLET", "片", 70);
        seedOptionIfAbsent("doseUnit", "CAPSULE", "粒", 60);
        seedOptionIfAbsent("doseUnit", "BAG", "袋", 50);
        seedOptionIfAbsent("doseUnit", "BOTTLE", "支", 40);
        seedOptionIfAbsent("doseUnit", "SPRAY", "喷", 30);
        seedOptionIfAbsent("doseUnit", "OTHER", "其他", 20);
    }

    /** 给药途径（处方笺用法）。 */
    private void ensureMedicationUsageOptions() {
        seedOptionIfAbsent("medicationUsage", "ORAL", "口服", 100);
        seedOptionIfAbsent("medicationUsage", "INJECTION", "注射", 90);
        seedOptionIfAbsent("medicationUsage", "INHALATION", "吸入", 80);
    }

    /** 用药频率（处方常用拉丁缩写）。 */
    private void ensureMedicationFrequencyOptions() {
        seedOptionIfAbsent("medicationFrequency", "QD", "每日1次 (qd)", 100);
        seedOptionIfAbsent("medicationFrequency", "BID", "每日2次 (bid)", 90);
        seedOptionIfAbsent("medicationFrequency", "TID", "每日3次 (tid)", 80);
        seedOptionIfAbsent("medicationFrequency", "QID", "每日4次 (qid)", 70);
        seedOptionIfAbsent("medicationFrequency", "QN", "每晚1次 (qn)", 60);
        seedOptionIfAbsent("medicationFrequency", "QOD", "隔日1次 (qod)", 50);
        seedOptionIfAbsent("medicationFrequency", "PRN", "必要时 (prn)", 40);
        seedOptionIfAbsent("medicationFrequency", "OTHER", "其他", 20);
    }

    /** 糖尿病病种档案字段（幂等；对齐公卫「基本信息」表单）。 */
    private void ensureDiabetesArchiveExtensions() {
        seedDiabetesTypeOptions();
        seedDiabetesSymptomOptions();
        seedDiabetesEmergencyComplicationOptions();
        seedDiabetesHypoglycemiaReactionOptions();

        seedDiseaseFieldIfAbsent(
                "diabetes",
                "diabetesType",
                "糖尿病类型",
                DictFieldSchemas.selectField(
                        MetaDataCodeEnum.DISEASE_DIABETES_TYPE, "糖尿病类型", "diabetesType"),
                100);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "diagnosisDate",
                "确诊时间",
                DictFieldSchemas.textField(MetaDataCodeEnum.DISEASE_DIABETES_DIAGNOSIS_DATE, "确诊时间"),
                95);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "symptoms",
                "糖尿病症状",
                DictFieldSchemas.multiSelectField(
                        MetaDataCodeEnum.DISEASE_DIABETES_SYMPTOMS, "糖尿病症状", "diabetesSymptoms"),
                90);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "symptomsOther",
                "其它症状",
                DictFieldSchemas.textField(MetaDataCodeEnum.DISEASE_DIABETES_SYMPTOMS_OTHER, "其它症状"),
                85);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "emergencyComplications",
                "紧急并发症",
                DictFieldSchemas.multiSelectField(
                        MetaDataCodeEnum.DISEASE_DIABETES_EMERGENCY_COMPLICATIONS,
                        "紧急并发症",
                        "diabetesEmergencyComplications"),
                80);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "hypoglycemiaReaction",
                "低血糖反应（公卫）",
                DictFieldSchemas.selectField(
                        MetaDataCodeEnum.DISEASE_DIABETES_HYPOGLYCEMIA_REACTION,
                        "低血糖反应（公卫）",
                        "diabetesHypoglycemiaReaction"),
                70);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "hypoglycemiaCountLastMonth",
                "近一个月发生过低血糖",
                DictFieldSchemas.numberField(
                        MetaDataCodeEnum.DISEASE_DIABETES_HYPOGLYCEMIA_COUNT, "近一个月发生过低血糖"),
                60);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "hypoglycemiaHandling",
                "发生低血糖时如何处理",
                DictFieldSchemas.textField(
                        MetaDataCodeEnum.DISEASE_DIABETES_HYPOGLYCEMIA_HANDLING, "发生低血糖时如何处理"),
                50);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "remark",
                "备注",
                DictFieldSchemas.textField(MetaDataCodeEnum.DISEASE_DIABETES_REMARK, "备注"),
                40);
        log.warn("Ensured diabetes disease-archive dict extensions");
    }

    /** 高血压病种档案字段（幂等；对齐公卫「基本信息」表单）。 */
    private void ensureHypertensionArchiveExtensions() {
        seedHypertensionTypeOptions();
        seedHypertensionGradeOptions();
        seedHypertensionCvRiskOptions();
        seedHypertensionSymptomOptions();
        seedHypertensionEmergencyComplicationOptions();

        seedDiseaseFieldIfAbsent(
                "hypertension",
                "hypertensionType",
                "高血压类型",
                DictFieldSchemas.selectField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_TYPE, "高血压类型", "hypertensionType"),
                100);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "diagnosisDate",
                "确诊时间",
                DictFieldSchemas.textField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_DIAGNOSIS_DATE, "确诊时间"),
                95);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "hypertensionGrade",
                "高血压分级",
                DictFieldSchemas.selectField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_GRADE,
                        "高血压分级",
                        "hypertensionGrade"),
                90);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "cvRiskStratification",
                "高血压心血管风险分层",
                DictFieldSchemas.selectField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_CV_RISK,
                        "高血压心血管风险分层",
                        "hypertensionCvRisk"),
                85);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "highestSystolic",
                "既往最高收缩压",
                DictFieldSchemas.numberField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_HIGHEST_SYS, "既往最高收缩压"),
                80);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "highestDiastolic",
                "既往最高舒张压",
                DictFieldSchemas.numberField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_HIGHEST_DIA, "既往最高舒张压"),
                75);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "symptoms",
                "症状表现",
                DictFieldSchemas.multiSelectField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_SYMPTOMS,
                        "症状表现",
                        "hypertensionSymptoms"),
                70);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "symptomsOther",
                "其它症状",
                DictFieldSchemas.textField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_SYMPTOMS_OTHER, "其它症状"),
                65);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "emergencyComplications",
                "紧急并发症",
                DictFieldSchemas.multiSelectField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_EMERGENCY_COMPLICATIONS,
                        "紧急并发症",
                        "hypertensionEmergencyComplications"),
                60);
        seedDiseaseFieldIfAbsent(
                "hypertension",
                "emergencyComplicationsOther",
                "其它紧急并发症",
                DictFieldSchemas.textField(
                        MetaDataCodeEnum.DISEASE_HYPERTENSION_EMERGENCY_COMPLICATIONS_OTHER,
                        "其它紧急并发症"),
                55);
        log.warn("Ensured hypertension disease-archive dict extensions");
    }

    private void seedHypertensionTypeOptions() {
        seedOptionEnsure("hypertensionType", "PRIMARY", "原发性高血压", 100);
        seedOptionEnsure("hypertensionType", "SECONDARY", "继发性高血压", 90);
        seedOptionEnsure("hypertensionType", "OTHER", "其他类型高血压", 80);
    }

    private void seedHypertensionGradeOptions() {
        seedOptionEnsure(
                "hypertensionGrade",
                "GRADE_1",
                "1级高血压（轻度）：收缩压140-159mmHg和/或舒张压90-99mmHg",
                100);
        seedOptionEnsure(
                "hypertensionGrade",
                "GRADE_2",
                "2级高血压（中度）：收缩压160-179mmHg和/或舒张压100-109mmHg",
                90);
        seedOptionEnsure(
                "hypertensionGrade",
                "GRADE_3",
                "3级高血压（重度）：收缩压≥180 mmHg和/或舒张压≥110mmHg",
                80);
        seedOptionEnsure(
                "hypertensionGrade",
                "ISOLATED_SYSTOLIC",
                "单纯收缩期高血压：收缩压≥140 mmHg和舒张压<90mmHg",
                70);
        seedOptionEnsure(
                "hypertensionGrade",
                "ISOLATED_DIASTOLIC",
                "单纯舒张期高血压：收缩压<140 mmHg和舒张压≥90mmHg",
                60);
        seedOptionEnsure("hypertensionGrade", "OTHER", "其它", 50);
    }

    private void seedHypertensionCvRiskOptions() {
        seedOptionIfAbsent("hypertensionCvRisk", "LOW", "低危", 100);
        seedOptionIfAbsent("hypertensionCvRisk", "MODERATE", "中危", 90);
        seedOptionIfAbsent("hypertensionCvRisk", "HIGH", "高危", 80);
        seedOptionIfAbsent("hypertensionCvRisk", "VERY_HIGH", "很高危", 70);
    }

    private void seedHypertensionSymptomOptions() {
        seedOptionIfAbsent("hypertensionSymptoms", "NONE", "无症状", 170);
        seedOptionIfAbsent("hypertensionSymptoms", "HEADACHE", "头疼", 160);
        seedOptionIfAbsent("hypertensionSymptoms", "DIZZINESS", "头晕", 150);
        seedOptionIfAbsent("hypertensionSymptoms", "NAUSEA", "恶心", 140);
        seedOptionIfAbsent("hypertensionSymptoms", "VOMITING", "呕吐", 130);
        seedOptionIfAbsent("hypertensionSymptoms", "PALPITATION", "心悸", 120);
        seedOptionIfAbsent("hypertensionSymptoms", "CHEST_TIGHTNESS", "胸闷", 110);
        seedOptionIfAbsent("hypertensionSymptoms", "CHEST_PAIN", "胸痛", 100);
        seedOptionIfAbsent("hypertensionSymptoms", "BLURRED_VISION_TINNITUS", "眼花耳鸣", 90);
        seedOptionIfAbsent("hypertensionSymptoms", "DYSPNEA", "呼吸困难", 80);
        seedOptionIfAbsent("hypertensionSymptoms", "LIMB_NUMBNESS", "四肢发麻", 70);
        seedOptionIfAbsent("hypertensionSymptoms", "LOWER_LIMB_EDEMA", "下肢水肿", 60);
        seedOptionIfAbsent("hypertensionSymptoms", "EPISTAXIS", "鼻衄出血不止", 50);
        seedOptionIfAbsent("hypertensionSymptoms", "NOCTURIA", "夜尿增多", 40);
        seedOptionIfAbsent("hypertensionSymptoms", "FOAMY_URINE", "泡沫尿", 30);
        seedOptionIfAbsent("hypertensionSymptoms", "CONSTIPATION", "便秘", 20);
        seedOptionIfAbsent("hypertensionSymptoms", "OTHER", "其它", 10);
    }

    private void seedHypertensionEmergencyComplicationOptions() {
        seedOptionIfAbsent("hypertensionEmergencyComplications", "AORTIC_DISSECTION", "主动脉夹层", 100);
        seedOptionIfAbsent(
                "hypertensionEmergencyComplications", "HYPERTENSIVE_ENCEPHALOPATHY", "高血压脑病", 90);
        seedOptionIfAbsent(
                "hypertensionEmergencyComplications", "AHF", "急性心力衰竭(AHF)", 80);
        seedOptionIfAbsent(
                "hypertensionEmergencyComplications", "ACS", "急性冠脉综合征(ACS)", 70);
        seedOptionIfAbsent(
                "hypertensionEmergencyComplications",
                "HYPERTENSIVE_CRISIS",
                "高血压危象（高血压急症, 高血压亚急症）",
                60);
        seedOptionIfAbsent("hypertensionEmergencyComplications", "OTHER", "其它", 50);
    }

    private void seedDiabetesTypeOptions() {
        seedOptionIfAbsent("diabetesType", "TYPE_1", "1型糖尿病", 100);
        seedOptionIfAbsent("diabetesType", "TYPE_2", "2型糖尿病", 90);
        seedOptionIfAbsent("diabetesType", "GESTATIONAL", "妊娠期糖尿病", 80);
        seedOptionIfAbsent("diabetesType", "SPECIAL", "特殊类型糖尿病", 70);
    }

    private void seedDiabetesSymptomOptions() {
        seedOptionIfAbsent("diabetesSymptoms", "NONE", "无症状", 100);
        seedOptionIfAbsent("diabetesSymptoms", "POLY_TRIAD", "多饮、多食、多尿", 90);
        seedOptionIfAbsent("diabetesSymptoms", "WEIGHT_LOSS", "体重减轻", 80);
        seedOptionIfAbsent(
                "diabetesSymptoms",
                "RECURRENT_BOILS_SLOW_HEALING",
                "反复生疖长痈、皮肤损伤或手术后伤口不愈合",
                70);
        seedOptionIfAbsent("diabetesSymptoms", "FOAMY_URINE", "尿中有泡沫", 60);
        seedOptionIfAbsent("diabetesSymptoms", "LOWER_LIMB_NEUROPATHY", "下肢麻木、烧灼、踩棉感", 50);
        seedOptionIfAbsent("diabetesSymptoms", "SKIN_ITCHING", "皮肤瘙痒", 40);
        seedOptionIfAbsent("diabetesSymptoms", "WEIGHT_GAIN", "体重增加", 30);
        seedOptionIfAbsent("diabetesSymptoms", "OTHER", "其它症状", 20);
    }

    private void seedDiabetesEmergencyComplicationOptions() {
        seedOptionIfAbsent("diabetesEmergencyComplications", "KETOACIDOSIS", "酮症酸中毒", 100);
        seedOptionIfAbsent("diabetesEmergencyComplications", "HYPOGLYCEMIA", "低血糖", 90);
        seedOptionIfAbsent("diabetesEmergencyComplications", "HYPEROSMOLAR_COMA", "高渗性昏迷", 80);
        seedOptionIfAbsent("diabetesEmergencyComplications", "LACTIC_ACIDOSIS", "乳酸酸中毒", 70);
    }

    private void seedDiabetesHypoglycemiaReactionOptions() {
        seedOptionIfAbsent("diabetesHypoglycemiaReaction", "OCCASIONAL", "偶尔", 100);
        seedOptionIfAbsent("diabetesHypoglycemiaReaction", "FREQUENT", "频繁", 90);
    }

    private void seedPresentIllnessOptions() {
        seedOption("presentIllness", "NONE", "无", 260, null);
        seedOption("presentIllness", "HYPERTENSION", "高血压", 250, null);
        seedOption("presentIllness", "CHD", "冠心病", 245, null);
        seedOption("presentIllness", "STROKE", "脑卒中", 240, null);
        seedOption("presentIllness", "HEART_FAILURE", "心力衰竭", 235, null);
        seedOption("presentIllness", "ATRIAL_FIBRILLATION", "心房颤动", 230, null);
        seedOption("presentIllness", "DIABETES", "2型糖尿病", 225, null);
        seedOption("presentIllness", "HYPERLIPIDEMIA", "高脂血症", 220, null);
        seedOption("presentIllness", "GOUT", "高尿酸血症/痛风", 215, null);
        seedOption("presentIllness", "COPD", "慢性阻塞性肺疾病", 210, null);
        seedOption("presentIllness", "ASTHMA", "支气管哮喘", 205, null);
        seedOption("presentIllness", "CKD", "慢性肾脏病", 200, null);
        seedOption("presentIllness", "CHRONIC_HEPATITIS", "慢性肝炎", 195, null);
        seedOption("presentIllness", "CIRRHOSIS", "肝硬化", 190, null);
        seedOption("presentIllness", "NAFLD", "脂肪肝", 185, null);
        seedOption("presentIllness", "CHRONIC_GI_DISEASE", "慢性胃炎/消化性溃疡", 180, null);
        seedOption("presentIllness", "OSTEOPOROSIS", "骨质疏松症", 175, null);
        seedOption("presentIllness", "RHEUMATOID_ARTHRITIS", "类风湿关节炎", 170, null);
        seedOption("presentIllness", "OSTEOARTHRITIS", "骨关节炎", 165, null);
        seedOption("presentIllness", "ALZHEIMERS", "阿尔茨海默病", 160, null);
        seedOption("presentIllness", "PARKINSONS", "帕金森病", 155, null);
        seedOption("presentIllness", "DEPRESSION", "抑郁症", 150, null);
        seedOption("presentIllness", "ANXIETY", "焦虑症", 145, null);
        seedOption("presentIllness", "MALIGNANT_TUMOR", "恶性肿瘤", 140, null);
        seedOption("presentIllness", "BPH", "良性前列腺增生", 135, null);
        seedOption("presentIllness", "THYROID_DISORDER", "甲状腺功能减退/亢进", 130, null);
    }

    private void seedDisease(String code, String desc, int sort) {
        insert(DictTypeEnum.DISEASE.name(), "0", code, desc, "{}", sort);
    }

    private void seedBasicField(String code, String desc, String content, int sort) {
        insert(DictTypeEnum.FIELD.name(), "basic_archive", code, desc, content, sort);
    }

    private void seedDiseaseField(String parent, String code, String desc, String content, int sort) {
        insert(DictTypeEnum.DISEASE_FIELD.name(), parent, code, desc, content, sort);
    }

    private void seedDiseaseFieldIfAbsent(
            String parent, String code, String desc, String content, int sort) {
        if (hasDict(DictTypeEnum.DISEASE_FIELD.name(), parent, code)) {
            return;
        }
        seedDiseaseField(parent, code, desc, content, sort);
    }

    private void seedOption(String parent, String code, String desc, int sort, String content) {
        insert(DictTypeEnum.OPTION.name(), parent, code, desc, content == null ? "{}" : content, sort);
    }

    private void seedOptionIfAbsent(String parent, String code, String desc, int sort) {
        seedOptionIfAbsent(parent, code, desc, sort, null);
    }

    private void seedOptionIfAbsent(String parent, String code, String desc, int sort, String content) {
        if (hasDict(DictTypeEnum.OPTION.name(), parent, code)) {
            return;
        }
        seedOption(parent, code, desc, sort, content);
    }

    /** 不存在则插入；已存在则同步文案与排序（平台种子纠偏）。 */
    private void seedOptionEnsure(String parent, String code, String desc, int sort) {
        List<SysDict> rows =
                sysDictMapper.listByTypeAndParent(
                        DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), parent);
        SysDict existing =
                rows.stream().filter(r -> code.equals(r.getDictCode())).findFirst().orElse(null);
        if (existing == null) {
            seedOption(parent, code, desc, sort, null);
            return;
        }
        if (desc.equals(existing.getDictCodeDesc())
                && existing.getSortOrder() != null
                && existing.getSortOrder() == sort) {
            return;
        }
        existing.setDictCodeDesc(desc);
        existing.setSortOrder(sort);
        EntityMeta.onUpdate(existing);
        sysDictMapper.updateDescAndSort(existing);
    }

    private boolean hasDict(String dictType, String parentCode, String dictCode) {
        List<SysDict> rows =
                sysDictMapper.listByTypeAndParent(DictService.PLATFORM_TENANT, dictType, parentCode);
        return rows.stream().anyMatch(r -> dictCode.equals(r.getDictCode()));
    }

    private void insert(String type, String parent, String code, String desc, String content, int sort) {
        SysDict row = new SysDict();
        row.setDictType(type);
        row.setParentCode(parent);
        row.setDictCode(code);
        row.setDictCodeDesc(desc);
        row.setContent(content);
        row.setSortOrder(sort);
        row.setTenantId(DictService.PLATFORM_TENANT);
        EntityMeta.onCreate(row);
        sysDictMapper.insert(row);
    }
}
